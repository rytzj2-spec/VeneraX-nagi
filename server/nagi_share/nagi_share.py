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


class Room:
    def __init__(self, rid, password, host_ws, host_name):
        self.id = rid
        self.password = password or ""
        self.host = host_ws
        self.host_name = host_name
        self.viewers = {}          # peer_id -> (ws, name)
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


async def ice_servers(tag):
    servers = [{"urls": u} for u in CFG.get("stun", ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"])]
    mode = CFG.get("turn", "none")
    try:
        if mode == "cloudflare":
            servers += await asyncio.get_running_loop().run_in_executor(None, _cloudflare_ice)
        elif mode == "coturn":
            servers += _coturn_ice(tag)
    except Exception as e:  # TURN 拿不到也能用（只是直连不上时就看不了）
        log.warning("TURN 凭据获取失败（%s）：%s", mode, e)
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


async def handler(ws):
    role = None
    room = None
    peer_id = None
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
                if len(ROOMS) >= int(CFG.get("max_rooms", 10)):
                    await send(ws, {"t": "error", "msg": "服务器上的房间太多了，稍后再试"})
                    continue
                rid = new_room_id()
                room = Room(rid, msg.get("pw"), ws, clean_name(msg.get("name"), "房主"))
                ROOMS[rid] = room
                role = "host"
                await send(ws, {"t": "hosted", "room": rid, "ice": await ice_servers("h-" + rid),
                                "maxViewers": int(CFG.get("max_viewers", 6))})
                log.info("开房间 %s（%s）", rid, ip)

            elif t == "join" and role is None:
                r = ROOMS.get((msg.get("room") or "").strip().lower())
                if r is None:
                    await send(ws, {"t": "error", "msg": "房间不存在或已经关了"})
                    continue
                if r.password and msg.get("pw") != r.password:
                    await send(ws, {"t": "error", "msg": "需要房间密码" if not msg.get("pw") else "密码不对", "needPw": True})
                    continue
                if len(r.viewers) >= int(CFG.get("max_viewers", 6)):
                    await send(ws, {"t": "error", "msg": "房间满了"})
                    continue
                room = r
                role = "viewer"
                peer_id = secrets.token_hex(4)
                name = clean_name(msg.get("name"), "观众" + peer_id[:3])
                r.viewers[peer_id] = (ws, name)
                await send(ws, {"t": "joined", "peer": peer_id, "host": r.host_name,
                                "ice": await ice_servers("v-" + r.id)})
                await send(r.host, {"t": "peer-join", "peer": peer_id, "name": name})
                log.info("进房间 %s 观众 %s（%s）", r.id, peer_id, ip)

            elif t == "signal" and room is not None:
                data = msg.get("data")
                if role == "host":
                    v = room.viewers.get(msg.get("to"))
                    if v:
                        await send(v[0], {"t": "signal", "data": data})
                elif role == "viewer":
                    await send(room.host, {"t": "signal", "from": peer_id, "data": data})

            elif t == "chat" and room is not None:
                text = (msg.get("text") or "").strip()[:200]
                if not text:
                    continue
                who = room.host_name if role == "host" else room.viewers.get(peer_id, (None, "?"))[1]
                out = {"t": "chat", "name": who, "text": text, "host": role == "host"}
                targets = [room.host] + [v[0] for v in room.viewers.values()]
                await asyncio.gather(*(send(w, out) for w in targets))

            elif t == "ping":
                await send(ws, {"t": "pong"})
    except websockets.ConnectionClosed:
        pass
    finally:
        if role == "host" and room is not None:
            ROOMS.pop(room.id, None)
            await asyncio.gather(*(send(v[0], {"t": "closed"}) for v in room.viewers.values()))
            for v in room.viewers.values():
                try:
                    await v[0].close()
                except Exception:
                    pass
            log.info("关房间 %s", room.id)
        elif role == "viewer" and room is not None:
            room.viewers.pop(peer_id, None)
            await send(room.host, {"t": "peer-leave", "peer": peer_id})


async def main(cfg_path):
    global CFG
    with open(cfg_path, encoding="utf-8") as f:
        CFG = json.load(f)
    host = CFG.get("listen_host", "127.0.0.1")
    port = int(CFG.get("listen_port", 8090))
    log.info("Nagisaland 屏幕共享牵线服务 %s:%d，TURN=%s", host, port, CFG.get("turn", "none"))
    async with websockets.serve(handler, host, port, max_size=64 * 1024, ping_interval=20, ping_timeout=20):
        await asyncio.Future()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="/opt/nagi-share/config.json")
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(main(a.config))
