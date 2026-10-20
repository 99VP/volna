#!/usr/bin/env python3
"""Tiny watch-together room server for Volna.

Standard library only. Rooms live in memory and expire after 6 hours.
Run behind HTTPS (for example, an SSH tunnel to localhost.run) because the
Android app does not allow cleartext traffic.

    python3 server.py --host 127.0.0.1 --port 8787
"""

import argparse
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOMS = {}
LOCK = threading.Lock()
TTL = 6 * 60 * 60


def cleanup():
    now = time.time()
    with LOCK:
        for key in [k for k, v in ROOMS.items() if now - v["updated"] > TTL]:
            ROOMS.pop(key, None)


def room_view(room):
    return {
        "video": room.get("video", ""),
        "position": int(room.get("position", 0)),
        "playing": bool(room.get("playing", False)),
        "updatedAt": int(room.get("updated", 0) * 1000),
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload=b"", content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode("utf-8"))

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > 128 * 1024:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def do_OPTIONS(self):
        self._send(204)

    def do_POST(self):
        cleanup()
        if self.path == "/api/rooms":
            data = self._read_json()
            room_id = secrets.token_urlsafe(6)
            with LOCK:
                ROOMS[room_id] = {
                    "video": str(data.get("video", ""))[:2048],
                    "position": max(0, int(data.get("position", 0) or 0)),
                    "playing": bool(data.get("playing", False)),
                    "updated": time.time(),
                }
            return self._json(200, {"id": room_id})

        if self.path.startswith("/api/rooms/"):
            room_id = self.path.rsplit("/", 1)[-1]
            data = self._read_json()
            with LOCK:
                room = ROOMS.get(room_id)
                if room is None:
                    return self._json(404, {"error": "room not found"})
                video = str(data.get("video", "")).strip()
                if video:
                    room["video"] = video[:2048]
                room["position"] = max(0, int(data.get("position", 0) or 0))
                room["playing"] = bool(data.get("playing", False))
                room["updated"] = time.time()
                return self._json(200, room_view(room))

        self._json(404, {"error": "not found"})

    def do_GET(self):
        cleanup()
        if self.path.startswith("/api/rooms/"):
            room_id = self.path.rsplit("/", 1)[-1]
            with LOCK:
                room = ROOMS.get(room_id)
                if room is None:
                    return self._json(404, {"error": "room not found"})
                return self._json(200, room_view(room))
        self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Watch rooms: http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
