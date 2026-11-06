#!/usr/bin/env python3
"""A stand-in for party-service inside the lab: names for the statement, nothing else.

The statement service asks ``GET /api/v1/parties/batch?ids=a,b,c`` for the holders' names and RFC.
The lab does not run party-service (it needs identity, compliance and a few more), so this answers
with a deterministic generic persona per id. Real names never appear in lab material.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

ROLES = ["Titular de prueba", "Distribuidora de prueba", "Cliente de prueba"]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (http.server naming)
        url = urlparse(self.path)
        if url.path != "/api/v1/parties/batch":
            self.send_response(404)
            self.end_headers()
            return
        ids = []
        for raw in parse_qs(url.query).get("ids", []):
            ids.extend(i for i in raw.split(",") if i)
        body = [
            {
                "partyId": pid,
                "firstName": ROLES[sum(map(ord, pid)) % len(ROLES)],
                "lastName1": pid[:4].upper(),
                "lastName2": None,
                "rfc": "XAXX010101000",
                "taxName": None,
            }
            for pid in ids
        ]
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):  # quiet
        return


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
