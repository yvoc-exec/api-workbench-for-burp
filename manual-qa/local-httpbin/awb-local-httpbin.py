#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
import json

TOKEN = "awb-oauth-local-access-token"

class Handler(BaseHTTPRequestHandler):
    server_version = "AWBLocalHTTPBin/1.0"

    def _json(self, status, data):
        body = json.dumps(data, sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        return self.rfile.read(length) if length else b""

    def do_POST(self):
        path = urlparse(self.path).path
        body = self._read_body()
        ctype = self.headers.get("Content-Type", "")
        data = {}
        if "application/json" in ctype and body:
            try:
                data = json.loads(body.decode("utf-8"))
            except Exception:
                data = {}
        elif body:
            data = {k: v[-1] if v else "" for k, v in parse_qs(body.decode("utf-8"), keep_blank_values=True).items()}
        if path == "/oauth/token":
            self._json(200, {
                "access_token": TOKEN,
                "token_type": "Bearer",
                "expires_in": 3600,
                "scope": "read write",
                "grant_type": data.get("grant_type", ""),
                "client_id": data.get("client_id", ""),
                "received_content_type": ctype,
                "awb_local": True,
            })
            return
        if path == "/oauth/introspect":
            token = data.get("token", "")
            self._json(200, {"active": token == TOKEN, "token": token, "awb_local": True})
            return
        self._json(404, {"error": "not_found", "path": path, "awb_local": True})

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/oauth/protected":
            auth = self.headers.get("Authorization", "")
            if auth == f"Bearer {TOKEN}":
                self._json(200, {"authenticated": True, "oauth": True, "token": TOKEN, "awb_local": True})
            else:
                self._json(401, {"authenticated": False, "oauth": True, "error": "missing_or_invalid_token"})
            return
        self._json(200, {"ok": True, "path": path, "awb_local": True})

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))

if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 18080), Handler)
    print("AWB local HTTPBin listening on http://127.0.0.1:18080")
    server.serve_forever()
