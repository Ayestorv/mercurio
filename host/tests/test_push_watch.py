"""Tests for host/mercurio-push-watch against a fake Herdr API socket.

Run: python3 -m unittest discover -s host/tests
"""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import queue
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parent.parent / "mercurio-push-watch"


def load_watch():
    loader = importlib.machinery.SourceFileLoader("mercurio_push_watch", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


pw = load_watch()


def pane(pane_id, status, agent="claude", tab="w1:t1"):
    return {
        "pane_id": pane_id,
        "agent_status": status,
        "agent": agent,
        "workspace_id": "w1",
        "tab_id": tab,
        "terminal_title": "SECRET PANE TEXT",
    }


class FakeHerdr:
    """Answers session.snapshot and events.subscribe like Herdr's API socket."""

    def __init__(self, path):
        self.path = path
        self.panes = []
        self.subscribes = []
        self.streams = []
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(path)
        self.server.listen()
        threading.Thread(target=self.serve, daemon=True).start()

    def snapshot(self):
        return {
            "panes": list(self.panes),
            "workspaces": [{"workspace_id": "w1", "label": "mercurio"}],
            "tabs": [{"tab_id": "w1:t1", "label": "build"}],
        }

    def serve(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        with conn:
            req = json.loads(conn.makefile().readline())
            if req["method"] == "session.snapshot":
                reply = {"id": req["id"], "result": {"type": "session_snapshot", "snapshot": self.snapshot()}}
                conn.sendall((json.dumps(reply) + "\n").encode())
                return
            events = queue.Queue()
            self.streams.append(events)
            self.subscribes.append(req["params"]["subscriptions"])
            conn.sendall((json.dumps({"id": req["id"], "result": {"type": "subscription_started"}}) + "\n").encode())
            while True:
                event = events.get()
                if event is None:
                    return  # drop the stream, as a Herdr restart does
                conn.sendall((json.dumps(event) + "\n").encode())

    def send(self, event):
        """Deliver to the newest subscription, the one the watcher is reading."""
        self.streams[-1].put(event)

    def status(self, pane_id, status):
        self.send(
            {
                "event": "pane.agent_status_changed",
                "data": {"pane_id": pane_id, "agent_status": status, "agent": "claude", "workspace_id": "w1"},
            }
        )

    def close(self):
        self.server.close()


def wait_for(check, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            return True
        time.sleep(0.02)
    return False


class WatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sock = os.path.join(self.tmp.name, "herdr.sock")
        self.endpoints = os.path.join(self.tmp.name, "push-endpoints")
        Path(self.endpoints).write_text("# phone\nhttps://push.example/upAbc?up=1\n\nnot-a-url\n")
        self.herdr = FakeHerdr(self.sock)
        self.posts = []
        self.clock = [100.0]

    def tearDown(self):
        if self.herdr.streams:
            self.herdr.send(None)
        self.herdr.close()
        self.tmp.cleanup()

    def start(self, states=("blocked",)):
        watcher = pw.Watcher(
            self.sock,
            "default",
            "sun",
            states,
            20.0,
            self.endpoints,
            post=lambda url, body: self.posts.append((url, json.loads(body))) or 200,
            clock=lambda: self.clock[0],
        )
        threading.Thread(target=watcher.run, daemon=True).start()
        self.assertTrue(wait_for(lambda: self.herdr.subscribes))
        return watcher

    def test_blocked_pushes_labels_and_no_pane_text(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start()
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))
        url, body = self.posts[0]
        self.assertEqual(url, "https://push.example/upAbc?up=1")
        self.assertEqual(body["event"], "pane.agent_status_changed")
        self.assertEqual(
            (body["state"], body["host"], body["session"], body["pane_id"], body["agent"]),
            ("blocked", "sun", "default", "w1:p1", "claude"),
        )
        self.assertEqual((body["workspace"], body["tab"]), ("mercurio", "build"))
        self.assertNotIn("SECRET", json.dumps(body))

    def test_already_blocked_at_start_is_silent(self):
        self.herdr.panes = [pane("w1:p1", "blocked")]
        self.start()
        self.herdr.status("w1:p1", "blocked")
        time.sleep(0.2)
        self.assertEqual(self.posts, [])

    def test_repeat_within_debounce_pushes_once(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start()
        for status in ("blocked", "working", "blocked"):
            self.herdr.status("w1:p1", status)
        time.sleep(0.2)
        self.assertEqual(len(self.posts), 1)
        self.clock[0] += 21
        self.herdr.status("w1:p1", "working")
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: len(self.posts) == 2))

    def test_only_chosen_states_push(self):
        self.herdr.panes = [pane("w1:p1", "working")]
        self.start(states=("blocked", "done"))
        for status in ("idle", "working", "done"):
            self.herdr.status("w1:p1", status)
        self.assertTrue(wait_for(lambda: self.posts))
        time.sleep(0.2)
        self.assertEqual([b["state"] for _, b in self.posts], ["done"])

    def test_new_pane_resubscribes_and_is_watched(self):
        self.herdr.panes = [pane("w1:p1", "idle")]
        self.start()
        self.herdr.panes.append(pane("w1:p2", "idle"))
        self.herdr.send({"event": "pane_created", "data": {"pane": pane("w1:p2", "idle")}})
        self.assertTrue(wait_for(lambda: len(self.herdr.subscribes) == 2))
        watched = [s.get("pane_id") for s in self.herdr.subscribes[1] if s["type"] == "pane.agent_status_changed"]
        self.assertEqual(watched, ["w1:p1", "w1:p2"])
        self.herdr.status("w1:p2", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))

    def test_reconnects_after_stream_drops(self):
        self.herdr.panes = [pane("w1:p1", "idle")]
        self.start()
        self.herdr.send(None)
        self.assertTrue(wait_for(lambda: len(self.herdr.subscribes) == 2))
        self.herdr.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))


class SessionsTest(unittest.TestCase):
    """Every running Herdr session is followed, each push names its session."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": self.tmp.name})
        self.env.start()
        os.environ.pop("HERDR_SOCKET_PATH", None)
        self.herdr = os.path.join(self.tmp.name, "herdr")
        os.makedirs(os.path.join(self.herdr, "sessions"))
        self.endpoints = os.path.join(self.tmp.name, "push-endpoints")
        Path(self.endpoints).write_text("https://push.example/upAbc\n")
        self.servers = {}
        self.posts = []

    def tearDown(self):
        for server in self.servers.values():
            if server.streams:
                server.send(None)
            server.close()
        self.env.stop()
        self.tmp.cleanup()

    def serve(self, name):
        path = pw.herdr_socket(name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        server = FakeHerdr(path)
        server.panes = [pane("w1:p1", "working")]
        self.servers[name] = server
        return server

    def make(self, sock_path, session):
        post = lambda url, body: self.posts.append(json.loads(body)) or 200  # noqa: E731
        return pw.Watcher(sock_path, session, "sun", ["blocked"], 20.0, self.endpoints, post=post)

    def test_finds_running_sessions_and_skips_a_stale_socket(self):
        self.serve("default")
        self.serve("work")
        stale = pw.herdr_socket("old")
        os.makedirs(os.path.dirname(stale))
        Path(stale).touch()
        self.assertEqual(sorted(pw.herdr_sessions()), ["default", "old", "work"])
        self.assertFalse(pw.running(stale))
        sessions = pw.Sessions(self.make)
        sessions.scan()
        self.assertEqual(sorted(sessions.threads), ["default", "work"])

    def test_each_session_pushes_under_its_own_name(self):
        default, work = self.serve("default"), self.serve("work")
        pw.Sessions(self.make).scan()
        self.assertTrue(wait_for(lambda: default.subscribes and work.subscribes))
        work.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: self.posts))
        default.status("w1:p1", "blocked")
        self.assertTrue(wait_for(lambda: len(self.posts) == 2))
        # The same pane id in two sessions: two pushes, told apart by the session.
        self.assertEqual([(b["session"], b["pane_id"]) for b in self.posts], [("work", "w1:p1"), ("default", "w1:p1")])

    def test_a_rescan_adds_a_new_session_once(self):
        self.serve("default")
        sessions = pw.Sessions(self.make)
        sessions.scan()
        first = sessions.threads["default"]
        late = self.serve("late")
        sessions.scan()
        self.assertIs(sessions.threads["default"], first)
        self.assertIn("late", sessions.threads)
        self.assertTrue(wait_for(lambda: late.subscribes))


class NoHerdrTest(unittest.TestCase):
    def test_exits_cleanly_without_herdr(self):
        with tempfile.TemporaryDirectory() as tmp:
            watcher = pw.Watcher(
                os.path.join(tmp, "missing", "herdr.sock"), "default", "sun", ["blocked"], 20.0, os.path.join(tmp, "e")
            )
            self.assertEqual(watcher.run(), 0)

    def test_sessions_exit_cleanly_without_herdr(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": tmp}):
            self.assertEqual(pw.Sessions(lambda sock, name: None).run(), 0)

    def test_endpoints_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "e")
            self.assertEqual(pw.read_endpoints(path), [])
            Path(path).write_text("# c\n http://a/upX \nftp://b\n\n")
            self.assertEqual(pw.read_endpoints(path), ["http://a/upX"])


if __name__ == "__main__":
    unittest.main()
