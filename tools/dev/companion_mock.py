#!/usr/bin/env python3
"""Serve the phone companion page with a fake TV behind it, to work on the page without a TV.

    python3 tools/dev/companion_mock.py      # then open http://localhost:8765, code 1234
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

PAGE = os.path.join(os.path.dirname(__file__), "..", "..", "launcher", "app", "src", "main", "res", "raw", "companion.html")
PIN = "1234"
STATE = {
    "apps": [{"pkg": p, "label": l} for p, l in [("com.netflix.ninja", "Netflix"), ("com.amazon.firetv.youtube", "YouTube"),
             ("com.amazon.firebat", "Prime Video"), ("com.disney.disneyplus", "Disney+"), ("com.hulu.plus", "Hulu"),
             ("com.hbo.hbonow", "HBO Max"), ("com.spotify.tv.android", "Spotify")]],
    "scenes": [{"id": "fire", "label": "Fireplace", "ready": True}, {"id": "rain", "label": "Rain on the window", "ready": False},
               {"id": "aquarium", "label": "Aquarium", "ready": False}, {"id": "snow", "label": "Snowfall", "ready": False},
               {"id": "ocean", "label": "Ocean at dusk", "ready": False}],
    "wallpapers": [{"name": "manga.jpg", "title": "Manga"}, {"name": "doctor_doom.jpg", "title": "Doctor doom"}],
    "current": "manga.jpg",
}
# A 1x1 dark JPEG stands in for thumbnails.
THUMB = bytes.fromhex("ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9")


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, kind, body):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _route(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/":
            return self._send(200, "text/html; charset=utf-8", open(PAGE, "rb").read())
        if q.get("pin") != PIN:
            return self._send(403, "application/json", b'{"error":"code"}')
        if url.path == "/api/state":
            return self._send(200, "application/json", json.dumps(STATE).encode())
        if url.path == "/api/thumb":
            return self._send(200, "image/jpeg", THUMB)
        if url.path == "/api/wallpaper":
            STATE["current"] = q.get("name", "")
        if url.path == "/api/upload":
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)
            name = q.get("name", "phone.jpg").lower().replace(" ", "_")
            STATE["wallpapers"].append({"name": name, "title": name.rsplit(".", 1)[0].replace("_", " ").capitalize()})
            STATE["current"] = name
        print("TV would:", url.path, q, file=sys.stderr)
        return self._send(200, "application/json", b'{"ok":true}')

    do_GET = do_POST = _route


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", int(os.environ.get("PORT", 8765))), Handler).serve_forever()
