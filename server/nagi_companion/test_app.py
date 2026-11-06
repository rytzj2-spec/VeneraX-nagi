import asyncio
import json
import tempfile
import time
import unittest
from pathlib import Path

from aiohttp.test_utils import TestClient, TestServer
from app import create_app


class CompanionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.key = "test-registration-key-not-for-production"
        self.app = create_app(str(Path(self.tmp.name) / "test.db"), self.key)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.a = await self.register("A")
        self.b = await self.register("B")
        self.c = await self.register("C")

    async def asyncTearDown(self):
        await self.client.close()
        self.tmp.cleanup()

    async def request(self, who, path, data=None, method="POST", status=200):
        response = await self.client.request(method, "/v1/" + path, json=data,
                                             headers={"Authorization": "Bearer " + who["token"]} if who else {})
        value = await response.json()
        self.assertEqual(response.status, status, value)
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        return value

    async def register(self, name):
        return await self.request(None, "register", {"name": name, "setup_key": self.key})

    async def pair(self, a=None, b=None):
        a, b = a or self.a, b or self.b
        code = (await self.request(a, "invite"))["code"]
        await self.request(b, "join", {"code": code})
        await self.request(a, "confirm", {"guest": b["id"]})

    async def enable(self, who=None, **extra):
        await self.request(who or self.a, "consent", {"sharing": True, "location": True, "status": True, "usage": True, **extra})

    def sample(self, id="sample-1", at=None):
        now = int(time.time()) if at is None else at
        return {"samples": [{"id": id, "at": now, "data": {
            "location": {"lat": 31.0, "lon": 121.0, "accuracy": 12, "speed": 1, "at": now},
            "status": {"battery": 70, "charging": False, "network": "Wi-Fi"},
            "usage": {"date": "2026-11-06", "screen_seconds": 10},
        }}]}

    async def test_credentials_and_setup_key(self):
        await self.request(None, "state", method="GET", status=401)
        await self.request({"token": "bad"}, "state", method="GET", status=401)
        await self.request(None, "register", {"setup_key": "bad", "name": "Bad"}, status=403)
        row = self.app["store"].device(self.a["id"])
        self.assertNotEqual(row["token"], self.a["token"])

    async def test_pair_requires_host_confirmation(self):
        code = (await self.request(self.a, "invite"))["code"]
        await self.request(self.b, "join", {"code": code})
        before = await self.request(self.b, "state", method="GET")
        self.assertNotIn("peer", before)
        await self.request(self.c, "confirm", {"guest": self.b["id"]}, status=409)
        await self.request(self.a, "confirm", {"guest": self.b["id"]})
        after = await self.request(self.b, "state", method="GET")
        self.assertEqual(after["peer"]["id"], self.a["id"])

    async def test_invite_single_use_and_no_self_pair(self):
        code = (await self.request(self.a, "invite"))["code"]
        await self.request(self.a, "join", {"code": code}, status=409)
        await self.request(self.b, "join", {"code": code})
        await self.request(self.c, "join", {"code": code}, status=409)
        await self.request(self.a, "confirm", {"guest": self.b["id"]})
        await self.request(self.c, "join", {"code": code}, status=409)

    async def test_invite_expiry(self):
        code = (await self.request(self.a, "invite"))["code"]
        self.app["store"].run("UPDATE invites SET expires=0")
        self.app["store"].db.commit()
        await self.request(self.b, "join", {"code": code}, status=409)

    async def test_unpaired_access_rejected(self):
        await self.pair()
        await self.request(self.c, "history", method="GET", status=409)
        await self.request(self.c, "events", method="GET", status=409)
        await self.request(self.c, "events", {"id": "1", "kind": "chat", "text": "hello"}, status=409)

    async def test_default_no_sharing_and_category_filters(self):
        await self.pair()
        await self.request(self.a, "samples", self.sample(), status=403)
        await self.enable(location=False, usage=False)
        await self.request(self.a, "samples", self.sample())
        state = await self.request(self.b, "state", method="GET")
        self.assertEqual(set(state["peer"]["sample"]["data"]), {"status"})

    async def test_pause_hides_current_history_and_events(self):
        await self.pair(); await self.enable()
        await self.request(self.a, "samples", self.sample())
        await self.request(self.a, "events", {"id": "e1", "kind": "unlock", "text": "unlocked"})
        await self.request(self.a, "consent", {"sharing": False})
        state = await self.request(self.b, "state", method="GET")
        self.assertNotIn("sample", state["peer"])
        history = await self.request(self.b, "history", method="GET")
        self.assertTrue(all(not row["data"] for row in history["samples"]))
        events = await self.request(self.b, "events", method="GET")
        self.assertEqual(events["events"], [])

    async def test_sample_retry_and_delayed_upload_order(self):
        await self.pair(); await self.enable()
        await self.request(self.a, "samples", self.sample())
        await self.request(self.a, "samples", self.sample())
        await self.request(self.a, "samples", self.sample("older", int(time.time()) - 100))
        self.assertEqual(self.app["store"].one("SELECT count(*) AS n FROM samples")["n"], 2)
        state = await self.request(self.b, "state", method="GET")
        self.assertGreater(state["peer"]["sample"]["at"], int(time.time()) - 10)

    async def test_malformed_location_rejects_whole_batch(self):
        await self.enable()
        data = self.sample()
        data["samples"].append(self.sample("bad")["samples"][0])
        data["samples"][1]["data"]["location"]["lat"] = 999
        await self.request(self.a, "samples", data, status=400)
        self.assertEqual(self.app["store"].one("SELECT count(*) AS n FROM samples")["n"], 0)

    async def test_command_requires_receiver_consent_and_expires(self):
        await self.pair()
        event = {"id": "ring1", "kind": "ring", "text": "ring"}
        await self.request(self.a, "events", event, status=403)
        await self.enable(self.b, ring=True)
        await self.request(self.a, "events", event)
        await self.request(self.a, "events", event)  # retry is idempotent
        events = await self.request(self.b, "events", method="GET")
        self.assertEqual(len(events["events"]), 1)
        self.app["store"].run("UPDATE events SET expires=1")
        self.app["store"].db.commit()
        events = await self.request(self.b, "events", method="GET")
        self.assertEqual(events["events"], [])

    async def test_command_rate_limit(self):
        await self.pair(); await self.enable(self.b, ring=True)
        await self.request(self.a, "events", {"id": "ring1", "kind": "ring", "text": "ring"})
        await self.request(self.a, "events", {"id": "ring2", "kind": "ring", "text": "ring"}, status=429)

    async def test_unpair_purges_and_revokes_both(self):
        await self.pair(); await self.enable()
        await self.request(self.a, "samples", self.sample())
        await self.request(self.a, "events", {"id": "chat", "kind": "chat", "text": "private"})
        await self.request(self.b, "unpair")
        self.assertEqual(self.app["store"].one("SELECT count(*) AS n FROM samples")["n"], 0)
        self.assertEqual(self.app["store"].one("SELECT count(*) AS n FROM events")["n"], 0)
        self.assertFalse(self.app["store"].device(self.a["id"])["peer"])
        await self.request(self.a, "samples", self.sample(), status=403)
        await self.pair(self.a, self.c)
        self.assertEqual((await self.request(self.c, "events", method="GET"))["events"], [])

    async def test_delete_device_revokes_token(self):
        await self.request(self.a, "device", method="DELETE")
        await self.request(self.a, "state", method="GET", status=401)

    async def test_retention_and_query_bounds(self):
        await self.pair(); await self.enable()
        await self.request(self.a, "samples", self.sample("old", int(time.time()) - 8 * 86400), status=400)
        await self.request(self.b, "history?start=0", method="GET", status=400)
        store = self.app["store"]
        store.run("INSERT INTO samples VALUES('old',?,0,'{}')", (self.a["id"],))
        store.prune()
        self.assertEqual(store.one("SELECT count(*) AS n FROM samples")["n"], 0)

    async def test_unknown_events_and_nonboolean_consent(self):
        await self.pair()
        await self.request(self.a, "events", {"kind": "execute", "text": "anything"}, status=400)
        await self.request(self.a, "consent", {"sharing": "true"}, status=400)

    async def test_inflight_upload_cannot_restore_revoked_sharing(self):
        await self.pair(); await self.enable()
        started, release = asyncio.Event(), asyncio.Event()
        encoded = json.dumps(self.sample()).encode()
        async def chunks():
            yield encoded[:10]
            started.set()
            await release.wait()
            yield encoded[10:]
        pending = asyncio.create_task(self.client.post('/v1/samples', data=chunks(), headers={
            'Authorization': 'Bearer ' + self.a['token'], 'Content-Type': 'application/json'}))
        await started.wait()
        await self.request(self.a, 'consent', {'sharing': False})
        release.set()
        response = await pending
        self.assertEqual(response.status, 403)
        self.assertEqual(self.app['store'].one('SELECT count(*) AS n FROM samples')['n'], 0)

    async def test_invalid_nested_data_is_rejected(self):
        await self.enable()
        for key in ('location', 'status', 'usage'):
            sample = self.sample()
            sample['samples'][0]['data'][key] = []
            await self.request(self.a, 'samples', sample, status=400)


if __name__ == "__main__":
    unittest.main()
