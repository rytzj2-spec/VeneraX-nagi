"""Private two-person companion API. Run behind HTTPS; no existing service is modified."""
import asyncio
import hashlib
import hmac
import json
import math
import os
import secrets
import sqlite3
import time
from collections import defaultdict, deque
from pathlib import Path

from aiohttp import web

DAY = 86400
GROUPS = {"location", "status", "usage"}
EVENT_GROUP = {
    "arrive": "location", "leave": "location", "travel": "location",
    "screen_on": "usage", "screen_off": "usage", "unlock": "usage",
    "charging": "status", "unplugged": "status", "network": "status", "call": "status",
    "morning": "status", "bedtime": "status", "water": "status",
}


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def fail(status, message):
    raise APIError(status, message)


class APIError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def clean_text(value, limit=200):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        fail(400, "文本为空或过长")
    return value.strip()


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
        fail(400, "数值超出范围")
    return value


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
                peer TEXT, consent TEXT NOT NULL DEFAULT '{}', seen INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS invites (
                code TEXT PRIMARY KEY, owner TEXT UNIQUE NOT NULL, expires INTEGER NOT NULL, guest TEXT);
            CREATE TABLE IF NOT EXISTS samples (
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS sample_time ON samples(owner, at);
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, nonce TEXT UNIQUE NOT NULL, owner TEXT NOT NULL,
                recipient TEXT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,
                expires INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS event_inbox ON events(recipient, id);
        """)

    def one(self, sql, args=()):
        return self.db.execute(sql, args).fetchone()

    def run(self, sql, args=()):
        return self.db.execute(sql, args)

    def device(self, ident):
        return self.one("SELECT * FROM devices WHERE id=?", (ident,))

    def prune(self):
        now = int(time.time())
        self.run("DELETE FROM samples WHERE at<?", (now - 7 * DAY,))
        self.run("DELETE FROM events WHERE at<? OR (expires>0 AND expires<?)", (now - 7 * DAY, now))
        self.run("DELETE FROM invites WHERE expires<?", (now,))
        self.db.commit()


def consent(row):
    return json.loads(row["consent"])


def filtered(data, permissions):
    return {k: v for k, v in data.items() if k in GROUPS and permissions.get(k) and permissions.get("sharing")}


def create_app(path="companion.sqlite3", setup_key=None):
    setup_key = setup_key or os.environ.get("NAGI_COMPANION_SETUP_KEY", "")
    if len(setup_key) < 24:
        raise ValueError("NAGI_COMPANION_SETUP_KEY must contain at least 24 characters")
    store = Store(path)
    limits = defaultdict(deque)

    @web.middleware
    async def middleware(request, handler):
        try:
            # Do not trust client-supplied X-Forwarded-For. Nginx should also limit public routes.
            key = request.remote or "unknown"
            now = time.monotonic()
            queue = limits[key]
            while queue and queue[0] < now - 60:
                queue.popleft()
            if len(queue) >= 240:
                fail(429, "请求过于频繁，请稍后重试")
            queue.append(now)
            if request.path != "/v1/register":
                token = request.headers.get("Authorization", "").removeprefix("Bearer ")
                device = store.one("SELECT * FROM devices WHERE token=?", (digest(token),)) if token else None
                if device is None:
                    fail(401, "设备凭据无效，请重新注册")
                request["device"] = device
            response = await handler(request)
        except APIError as exc:
            response = web.json_response({"error": exc.message}, status=exc.status)
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            response = web.json_response({"error": "请求格式错误"}, status=400)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    app = web.Application(middlewares=[middleware], client_max_size=128 * 1024)
    app["store"] = store

    async def body(request):
        value = await request.json()
        if not isinstance(value, dict):
            fail(400, "需要 JSON 对象")
        return value

    def current(request):
        device = store.device(request["device"]["id"])
        if device is None:
            fail(401, "设备已删除")
        return device

    def paired(request):
        device = current(request)
        peer = store.device(device["peer"]) if device["peer"] else None
        if peer is None or peer["peer"] != device["id"]:
            fail(409, "请先完成双方绑定")
        return device, peer

    async def register(request):
        data = await body(request)
        if not hmac.compare_digest(str(data.get("setup_key", "")), setup_key):
            fail(403, "服务器注册码错误")
        if store.one("SELECT count(*) AS n FROM devices")["n"] >= 20:
            fail(409, "私人服务设备数量已达上限，请先删除旧设备")
        token, ident = secrets.token_urlsafe(32), secrets.token_hex(16)
        store.run("INSERT INTO devices(id, token, name) VALUES(?,?,?)", (ident, digest(token), clean_text(data["name"], 32)))
        store.db.commit()
        return web.json_response({"id": ident, "token": token})

    async def invite(request):
        me = request["device"]
        if me["peer"]:
            fail(409, "请先解除现有绑定")
        code = secrets.token_hex(6).upper()
        store.run("DELETE FROM invites WHERE owner=? OR guest=?", (me["id"], me["id"]))
        store.run("INSERT INTO invites(code, owner, expires) VALUES(?,?,?)", (digest(code), me["id"], int(time.time()) + 600))
        store.db.commit()
        return web.json_response({"code": code, "expires_in": 600})

    async def join(request):
        data = await body(request)
        me = current(request)
        code = digest(clean_text(data["code"], 32).replace(" ", "").upper())
        inv = store.one("SELECT * FROM invites WHERE code=? AND expires>?", (code, int(time.time())))
        if me["peer"] or inv is None or inv["guest"] or inv["owner"] == me["id"]:
            fail(409, "邀请码无效、过期或设备已绑定")
        if store.device(inv["owner"])["peer"]:
            fail(409, "邀请方已绑定")
        store.run("DELETE FROM invites WHERE owner=? OR guest=?", (me["id"], me["id"]))
        store.run("UPDATE invites SET guest=? WHERE code=?", (me["id"], code))
        store.db.commit()
        return web.json_response({"pending": True})

    async def confirm(request):
        data = await body(request)
        me = current(request)
        inv = store.one("SELECT * FROM invites WHERE owner=? AND guest=? AND expires>?", (me["id"], data["guest"], int(time.time())))
        if inv is None or me["peer"] or store.device(inv["guest"])["peer"]:
            fail(409, "绑定申请已失效")
        store.run("UPDATE devices SET peer=? WHERE id=?", (inv["guest"], me["id"]))
        store.run("UPDATE devices SET peer=? WHERE id=?", (me["id"], inv["guest"]))
        store.run("DELETE FROM invites WHERE owner IN (?,?) OR guest IN (?,?)", (me["id"], inv["guest"], me["id"], inv["guest"]))
        store.db.commit()
        return web.json_response({"paired": True})

    async def set_consent(request):
        data = await body(request)
        me = current(request)
        allowed = GROUPS | {"sharing", "ring", "lock"}
        if set(data) - allowed or any(not isinstance(v, bool) for v in data.values()):
            fail(400, "共享选项必须是布尔值")
        value = {k: data.get(k, False) for k in allowed}
        store.run("UPDATE devices SET consent=? WHERE id=?", (json.dumps(value), me["id"]))
        store.db.commit()
        return web.json_response({"consent": value})

    async def state(request):
        me = request["device"]
        store.run("UPDATE devices SET seen=? WHERE id=?", (int(time.time()), me["id"]))
        store.db.commit()
        result = {"me": {"id": me["id"], "name": me["name"], "consent": consent(me)}}
        inv = store.one("SELECT guest FROM invites WHERE owner=? AND guest IS NOT NULL AND expires>?", (me["id"], int(time.time())))
        if inv:
            guest = store.device(inv["guest"])
            result["pending"] = {"id": guest["id"], "name": guest["name"]}
        if me["peer"]:
            peer = store.device(me["peer"])
            result["peer"] = {"id": peer["id"], "name": peer["name"], "consent": consent(peer), "seen": peer["seen"]}
            sample = store.one("SELECT * FROM samples WHERE owner=? AND at>? ORDER BY at DESC LIMIT 1", (peer["id"], int(time.time()) - 7 * DAY))
            if sample and consent(peer).get("sharing"):
                result["peer"]["sample"] = {"at": sample["at"], "data": filtered(json.loads(sample["data"]), consent(peer))}
        return web.json_response(result)

    def validate_sample(data):
        if not isinstance(data, dict) or set(data) - GROUPS:
            fail(400, "未知数据类型")
        result = {}
        if "location" in data:
            loc = data["location"]
            if not isinstance(loc, dict):
                fail(400, "定位格式错误")
            result["location"] = {"lat": number(loc["lat"], -90, 90), "lon": number(loc["lon"], -180, 180),
                                  "accuracy": number(loc["accuracy"], 0, 50000), "speed": number(loc.get("speed", 0), 0, 400),
                                  "at": int(number(loc["at"], int(time.time()) - 7 * DAY, int(time.time()) + 120))}
        if "status" in data:
            status = data["status"]
            if not isinstance(status, dict):
                fail(400, "状态格式错误")
            if not isinstance(status.get("charging"), bool):
                fail(400, "充电状态无效")
            result["status"] = {"battery": number(status["battery"], 0, 100), "charging": status["charging"],
                                "network": clean_text(status.get("network", "未知"), 80)}
        if "usage" in data:
            usage = data["usage"]
            if not isinstance(usage, dict):
                fail(400, "用量格式错误")
            result["usage"] = {"screen_seconds": number(usage["screen_seconds"], 0, DAY), "date": clean_text(usage["date"], 10)}
        return result

    async def upload(request):
        data = await body(request)
        me = current(request)
        permissions = consent(me)
        if not permissions.get("sharing"):
            fail(403, "共享已暂停")
        rows = data.get("samples", [])
        if not isinstance(rows, list) or len(rows) > 100:
            fail(400, "每批最多 100 条")
        validated = []
        for row in rows:
            at = int(number(row["at"], int(time.time()) - 7 * DAY, int(time.time()) + 120))
            validated.append((me["id"] + ":" + clean_text(row["id"], 64), me["id"], at,
                              json.dumps(filtered(validate_sample(row["data"]), permissions))))
        with store.db:
            store.db.executemany("INSERT OR IGNORE INTO samples VALUES(?,?,?,?)", validated)
        return web.json_response({"accepted": len(rows)})

    async def history(request):
        me, peer = paired(request)
        owner = me if request.query.get("who") == "me" else peer
        start = int(request.query.get("start", int(time.time()) - DAY))
        end = int(request.query.get("end", int(time.time()) + 1))
        if start < int(time.time()) - 7 * DAY - 60 or end - start > DAY + 3600 or end <= start:
            fail(400, "仅可查询最近七天，每次最多一天")
        rows = store.run("SELECT * FROM samples WHERE owner=? AND at>=? AND at<? ORDER BY at LIMIT 10000", (owner["id"], start, end))
        return web.json_response({"samples": [{"at": r["at"], "data": filtered(json.loads(r["data"]), consent(owner))} for r in rows]})

    async def send(request):
        data = await body(request)
        me, peer = paired(request)
        kind = data["kind"]
        if kind not in set(EVENT_GROUP) | {"chat", "sos", "ring", "lock"}:
            fail(400, "未知事件")
        if kind in EVENT_GROUP and not (consent(me).get("sharing") and consent(me).get(EVENT_GROUP[kind])):
            fail(403, "此类共享未开启")
        if kind in {"ring", "lock"} and not (consent(peer).get(kind) and consent(peer).get("sharing")):
            fail(403, "对方尚未允许此功能")
        text = clean_text(data.get("text", kind), 500)
        now = int(time.time())
        recent = store.one("SELECT at FROM events WHERE owner=? AND kind=? ORDER BY id DESC LIMIT 1", (me["id"], kind))
        nonce = me["id"] + ":" + clean_text(data["id"], 64)
        if store.one("SELECT id FROM events WHERE nonce=?", (nonce,)):
            return web.json_response({"accepted": True})
        if recent and now - recent["at"] < (30 if kind in {"ring", "lock", "sos"} else 1):
            fail(429, "请勿重复发送")
        at = int(number(data.get("at", now), now - 7 * DAY, now + 120))
        expires = now + 120 if kind in {"ring", "lock"} else 0
        store.run("INSERT INTO events(nonce,owner,recipient,at,kind,text,expires) VALUES(?,?,?,?,?,?,?)", (nonce, me["id"], peer["id"], at, kind, text, expires))
        store.db.commit()
        return web.json_response({"accepted": True})

    async def events(request):
        me, peer = paired(request)
        after = max(0, int(request.query.get("after", "0")))
        rows = list(store.run("SELECT * FROM events WHERE id>? AND ((recipient=? AND owner=?) OR (recipient=? AND owner=?)) AND at>? ORDER BY id LIMIT 200",
                              (after, me["id"], peer["id"], peer["id"], me["id"], int(time.time()) - 7 * DAY)))
        result = []
        for row in rows:
            permissions = consent(me if row["owner"] == me["id"] else peer)
            if row["kind"] in EVENT_GROUP and not (permissions.get("sharing") and permissions.get(EVENT_GROUP[row["kind"]])):
                continue
            if row["expires"] and row["expires"] < time.time():
                continue
            result.append({k: row[k] for k in ("id", "owner", "at", "kind", "text", "expires")})
        return web.json_response({"events": result, "cursor": rows[-1]["id"] if rows else after})

    async def clear(request):
        me = request["device"]
        store.run("DELETE FROM samples WHERE owner=?", (me["id"],))
        store.run("DELETE FROM events WHERE owner=?", (me["id"],))
        store.db.commit()
        return web.json_response({"deleted": True})

    def unpair_device(me):
        ids = (me["id"], me["peer"] or "")
        store.run("UPDATE devices SET peer=NULL, consent='{}' WHERE id IN (?,?)", ids)
        store.run("DELETE FROM invites WHERE owner IN (?,?) OR guest IN (?,?)", ids + ids)
        store.run("DELETE FROM samples WHERE owner IN (?,?)", ids)
        store.run("DELETE FROM events WHERE owner IN (?,?) OR recipient IN (?,?)", ids + ids)

    async def unpair(request):
        unpair_device(request["device"])
        store.db.commit()
        return web.json_response({"unpaired": True})

    async def delete_device(request):
        unpair_device(request["device"])
        store.run("DELETE FROM devices WHERE id=?", (request["device"]["id"],))
        store.db.commit()
        return web.json_response({"deleted": True})

    async def housekeeping(app):
        async def loop():
            while True:
                store.prune()
                now = time.monotonic()
                for key in list(limits):
                    if not limits[key] or limits[key][-1] < now - 60:
                        limits.pop(key, None)
                await asyncio.sleep(60)
        task = asyncio.create_task(loop())
        yield
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        store.db.close()

    app.cleanup_ctx.append(housekeeping)
    for method, path_, handler in [
        ("POST", "register", register), ("POST", "invite", invite), ("POST", "join", join),
        ("POST", "confirm", confirm), ("POST", "consent", set_consent), ("GET", "state", state),
        ("POST", "samples", upload), ("GET", "history", history), ("POST", "events", send),
        ("GET", "events", events), ("DELETE", "history", clear), ("POST", "unpair", unpair),
        ("DELETE", "device", delete_device),
    ]:
        app.router.add_route(method, "/v1/" + path_, handler)
    return app


if __name__ == "__main__":
    os.umask(0o077)
    db = Path(os.environ.get("NAGI_COMPANION_DB", "companion.sqlite3"))
    web.run_app(create_app(str(db)), host="127.0.0.1", port=int(os.environ.get("PORT", "8091")), access_log=None)
