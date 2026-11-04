# -*- coding: utf-8 -*-
"""
Nagisaland 屏幕共享 · 牵线服务（WebRTC signaling）

跑在小站那台服务器上（127.0.0.1:8090，Nginx 把 /share/ws 转进来）。只负责：
  - 开房间 / 进房间（房间号 + 可选密码，一个房主 + 最多 N 个观看者）
  - 在房主（手机 App）和观看者（浏览器）之间转发 WebRTC 的 offer / answer / ICE 候选
  - 给双方发 ICE 服务器（STUN + TURN 临时凭据：Cloudflare Realtime TURN 或自建 coturn，见 config.json）
  - 房间里的文字消息 / 弹幕（很小，顺手转发）
视频本身不经过这里：能直连就直连；连不上走 TURN（Cloudflare 或 coturn），也不经过这个进程。

依赖：websockets==12.0（venv 里装）。配置：/opt/nagi-share/config.json（不进 Git，见 config.example.json）。
运行：python3 nagi_share.py --config /opt/nagi-share/config.json
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import secrets
import time
import urllib.request
import urllib.error

import websockets

log = logging.getLogger("nagi-share")

CFG = {}
ROOMS = {}            # room_id -> Room
ROOM_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
_cf_cache = {"at": 0.0, "servers": None}


GRACE = 60          # 掉线后保留多少秒（房主 / 观看者在这段时间里重连 = 回到原来的位置）


class Viewer:
    def __init__(self, ws, name):
        self.ws = ws
        self.name = name
        self.key = secrets.token_urlsafe(12)     # 重连用
        self.gone_at = None


class Room:
    def __init__(self, rid, password, host_ws, host_name, turn):
        self.id = rid
        self.password = password or ""
        self.host = host_ws            # 房主掉线时为 None
        self.host_name = host_name
        self.host_key = secrets.token_urlsafe(12)
        self.host_gone_at = None
        self.turn = turn               # 房主选的中转：auto / cloudflare / coturn
        self.viewers = {}              # peer_id -> Viewer
        self.created = time.time()


# ---------------------------------------------------------------- ICE 服务器

def _cloudflare_ice():
    """Cloudflare Realtime TURN：用 Key ID / API Token 换临时凭据（缓存 1 小时，凭据本身 12 小时有效）"""
    now = time.time()
    if _cf_cache["servers"] and now - _cf_cache["at"] < 3600:
        return _cf_cache["servers"]
    cf = CFG.get("cloudflare") or {}
    url = "https://rtc.live.cloudflare.com/v1/turn/keys/%s/credentials/generate-ice-servers" % cf["key_id"]
    req = urllib.request.Request(
        url, data=json.dumps({"ttl": 43200}).encode(), method="POST",
        headers={"Authorization": "Bearer " + cf["api_token"], "Content-Type": "application/json",
                 # Cloudflare 会拦默认的 "Python-urllib/x.y" UA（403 / error 1010），换一个
                 "User-Agent": "nagi-share/1.0 (+https://github.com/rytzj2-spec)", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read()[:300].decode("utf-8", "replace")
        raise RuntimeError("HTTP %s: %s" % (e.code, body)) from None
    servers = data.get("iceServers") or []
    if isinstance(servers, dict):
        servers = [servers]
    # 浏览器连不了 53 端口（等超时），去掉
    for s in servers:
        urls = s.get("urls")
        if isinstance(urls, list):
            s["urls"] = [u for u in urls if ":53?" not in u and not u.endswith(":53")]
    _cf_cache.update(at=now, servers=servers)
    return servers


def _coturn_ice(tag):
    """自建 coturn（use-auth-secret）：用户名 = 过期时间:标记，密码 = base64(HMAC-SHA1(secret, 用户名))"""
    ct = CFG.get("coturn") or {}
    user = "%d:%s" % (int(time.time()) + int(ct.get("ttl", 43200)), tag)
    pw = base64.b64encode(hmac.new(ct["secret"].encode(), user.encode(), hashlib.sha1).digest()).decode()
    host = ct["host"]
    port = int(ct.get("port", 3478))
    return [{
        "urls": ["turn:%s:%d?transport=udp" % (host, port), "turn:%s:%d?transport=tcp" % (host, port)],
        "username": user,
        "credential": pw,
    }]


def turn_modes():
    """服务器上配好了哪些中转：config.json 的 turn = cloudflare / coturn / both / none"""
    mode = CFG.get("turn", "none")
    return {"cloudflare": ["cloudflare"], "coturn": ["coturn"], "both": ["coturn", "cloudflare"]}.get(mode, [])  # 自建（国内）排前面


async def ice_servers(tag, pref="auto"):
    servers = [{"urls": u} for u in CFG.get("stun", ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"])]
    modes = turn_modes()
    use = modes if pref in ("auto", "", None) else [m for m in modes if m == pref] or modes
    for m in use:
        try:
            if m == "cloudflare":
                servers += await asyncio.get_running_loop().run_in_executor(None, _cloudflare_ice)
            elif m == "coturn":
                servers += _coturn_ice(tag)
        except Exception as e:  # TURN 拿不到也能用（只是直连不上时就看不了）
            log.warning("TURN 凭据获取失败（%s）：%s", m, e)
    return servers


# ---------------------------------------------------------------- 收发

async def send(ws, obj):
    try:
        await ws.send(json.dumps(obj, ensure_ascii=False))
    except Exception:
        pass


def new_room_id():
    while True:
        rid = "".join(secrets.choice(ROOM_ALPHABET) for _ in range(6))
        if rid not in ROOMS:
            return rid


def clean_name(s, default):
    s = (s or "").strip()[:24]
    return s or default


async def close_room(room, reason):
    if ROOMS.get(room.id) is room:
        ROOMS.pop(room.id, None)
    out = {"t": "closed", "reason": reason}
    for v in list(room.viewers.values()):
        if v.ws is not None:
            await send(v.ws, out)
            try:
                await v.ws.close()
            except Exception:
                pass
    log.info("关房间 %s（%s）", room.id, reason)


async def reaper():
    """掉线超过 GRACE 秒还没回来的：房主 → 关房间；观看者 → 通知房主他走了"""
    while True:
        await asyncio.sleep(5)
        now = time.time()
        for room in list(ROOMS.values()):
            if room.host is None and room.host_gone_at and now - room.host_gone_at > GRACE:
                await close_room(room, "房主掉线超过 %d 秒" % GRACE)
                continue
            for pid, v in list(room.viewers.items()):
                if v.ws is None and v.gone_at and now - v.gone_at > GRACE:
                    room.viewers.pop(pid, None)
                    if room.host is not None:
                        await send(room.host, {"t": "peer-leave", "peer": pid})


async def handler(ws):
    role = None
    room = None
    peer_id = None
    me = None          # 观看者自己的 Viewer
    ip = ws.request_headers.get("X-Real-IP") or ws.request_headers.get("CF-Connecting-IP") or "?"
    try:
        async for raw in ws:
            if len(raw) > 64 * 1024:
                continue
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            t = msg.get("t")

            if t == "host" and role is None:
                res = msg.get("resume") or {}
                r = ROOMS.get(str(res.get("room") or ""))
                if r is not None and res.get("key") == r.host_key:
                    # 房主重连：回到原来的房间，已经建好的 WebRTC 连接不动
                    old = r.host
                    r.host = ws
                    r.host_gone_at = None
                    room, role = r, "host"
                    if old is not None and old is not ws:
                        try:
                            await old.close()
                        except Exception:
                            pass
                    await send(ws, {"t": "hosted", "room": r.id, "key": r.host_key, "resumed": True,
                                    "ice": await ice_servers("h-" + r.id, r.turn), "turnModes": turn_modes(),
                                    "viewers": [{"peer": p, "name": v.name, "online": v.ws is not None} for p, v in r.viewers.items()],
                                    "maxViewers": int(CFG.get("max_viewers", 6))})
                    for v in r.viewers.values():
                        if v.ws is not None:
                            await send(v.ws, {"t": "host-back"})
                    log.info("房主重连 %s（%s）", r.id, ip)
                    continue
                if res.get("room"):
                    await send(ws, {"t": "resume-failed", "msg": "原来的房间已经关了（掉线超过 %d 秒）" % GRACE})
                if len(ROOMS) >= int(CFG.get("max_rooms", 10)):
                    await send(ws, {"t": "error", "msg": "服务器上的房间太多了，稍后再试"})
                    continue
                turn = msg.get("turn") if msg.get("turn") in ("auto", "cloudflare", "coturn") else "auto"
                r = Room(new_room_id(), msg.get("pw"), ws, clean_name(msg.get("name"), "房主"), turn)
                ROOMS[r.id] = r
                room, role = r, "host"
                await send(ws, {"t": "hosted", "room": r.id, "key": r.host_key, "resumed": False,
                                "ice": await ice_servers("h-" + r.id, turn), "turnModes": turn_modes(),
                                "maxViewers": int(CFG.get("max_viewers", 6))})
                log.info("开房间 %s（%s，中转 %s）", r.id, ip, turn)

            elif t == "join" and role is None:
                r = ROOMS.get((msg.get("room") or "").strip().lower())
                if r is None:
                    await send(ws, {"t": "error", "msg": "房间不存在或已经关了"})
                    continue
                res = msg.get("resume") or {}
                v = r.viewers.get(str(res.get("peer") or ""))
                if v is not None and res.get("key") == v.key:
                    # 观看者重连：同一个身份，房主那边的连接不动
                    old = v.ws
                    v.ws, v.gone_at = ws, None
                    room, role, peer_id, me = r, "viewer", res.get("peer"), v
                    if old is not None and old is not ws:
                        try:
                            await old.close()
                        except Exception:
                            pass
                    await send(ws, {"t": "joined", "peer": peer_id, "key": v.key, "host": r.host_name, "resumed": True,
                                    "hostOnline": r.host is not None, "ice": await ice_servers("v-" + r.id, r.turn)})
                    log.info("观众重连 %s %s（%s）", r.id, peer_id, ip)
                    continue
                if r.password and msg.get("pw") != r.password:
                    await send(ws, {"t": "error", "msg": "需要房间密码" if not msg.get("pw") else "密码不对", "needPw": True})
                    continue
                if len(r.viewers) >= int(CFG.get("max_viewers", 6)):
                    await send(ws, {"t": "error", "msg": "房间满了"})
                    continue
                room, role = r, "viewer"
                peer_id = secrets.token_hex(4)
                me = Viewer(ws, clean_name(msg.get("name"), "观众" + peer_id[:3]))
                r.viewers[peer_id] = me
                await send(ws, {"t": "joined", "peer": peer_id, "key": me.key, "host": r.host_name, "resumed": False,
                                "hostOnline": r.host is not None, "ice": await ice_servers("v-" + r.id, r.turn)})
                if r.host is not None:
                    await send(r.host, {"t": "peer-join", "peer": peer_id, "name": me.name})
                log.info("进房间 %s 观众 %s（%s）", r.id, peer_id, ip)

            elif t == "signal" and room is not None:
                data = msg.get("data")
                if role == "host":
                    v = room.viewers.get(msg.get("to"))
                    if v is not None and v.ws is not None:
                        await send(v.ws, {"t": "signal", "data": data})
                elif role == "viewer" and room.host is not None:
                    await send(room.host, {"t": "signal", "from": peer_id, "data": data})

            elif t == "chat" and room is not None:
                text = (msg.get("text") or "").strip()[:200]
                if not text:
                    continue
                who = room.host_name if role == "host" else (me.name if me else "?")
                out = {"t": "chat", "name": who, "text": text, "host": role == "host"}
                targets = ([room.host] if room.host is not None else []) + [v.ws for v in room.viewers.values() if v.ws is not None]
                await asyncio.gather(*(send(w, out) for w in targets))

            elif t == "bye" and role == "host" and room is not None:
                # 房主主动结束（带原因）
                await close_room(room, (msg.get("reason") or "房主结束了共享")[:100])
                room = None
                role = "done"

            elif t == "leave" and role == "viewer" and room is not None:
                room.viewers.pop(peer_id, None)
                if room.host is not None:
                    await send(room.host, {"t": "peer-leave", "peer": peer_id})
                room = None
                role = "done"

            elif t == "ping":
                await send(ws, {"t": "pong"})
    except websockets.ConnectionClosed:
        pass
    finally:
        # 掉线：先保留 GRACE 秒等重连（reaper 到时间再关房间 / 通知房主）
        if role == "host" and room is not None and room.host is ws:
            room.host = None
            room.host_gone_at = time.time()
            for v in room.viewers.values():
                if v.ws is not None:
                    await send(v.ws, {"t": "host-away"})
            log.info("房主掉线 %s，保留 %d 秒", room.id, GRACE)
        elif role == "viewer" and room is not None and me is not None and me.ws is ws:
            me.ws = None
            me.gone_at = time.time()


async def main(cfg_path):
    global CFG
    with open(cfg_path, encoding="utf-8") as f:
        CFG = json.load(f)
    global GRACE
    GRACE = int(CFG.get("grace", GRACE))
    host = CFG.get("listen_host", "127.0.0.1")
    port = int(CFG.get("listen_port", 8090))
    log.info("Nagisaland 屏幕共享牵线服务 %s:%d，TURN=%s", host, port, CFG.get("turn", "none"))
    asyncio.get_running_loop().create_task(reaper())
    async with websockets.serve(handler, host, port, max_size=64 * 1024, ping_interval=20, ping_timeout=20):
        await asyncio.Future()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="/opt/nagi-share/config.json")
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(main(a.config))
