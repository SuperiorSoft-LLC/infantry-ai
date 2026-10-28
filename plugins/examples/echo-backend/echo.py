# PLUGIN-ENDPOINTS-2: the 30-line backend of the Acme_Echo example (plugins/GUIDE.md, "Your own backend in an hour").
# Run:  ECHO_KEY=dev-key python echo.py      (Python 3, standard library only; listens on http://localhost:8080)
# Answers POST/GET /echo with what it got, after checking the bearer key the Infantry AI host added to the request.
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer

KEY = os.environ.get("ECHO_KEY", "dev-key")
PORT = int(os.environ.get("ECHO_PORT", "8080"))

class Echo(BaseHTTPRequestHandler):
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_any(self):
        if self.headers.get("Authorization") != "Bearer " + KEY:
            return self.reply(401, {"error": "missing or wrong key"})
        size = int(self.headers.get("Content-Length") or 0)
        text = self.rfile.read(size).decode() if size else ""
        self.reply(200, {"method": self.command, "path": self.path, "echo": text})

    do_GET = handle_any
    do_POST = handle_any

if __name__ == "__main__":
    print(f"echo backend on http://localhost:{PORT}")
    HTTPServer(("localhost", PORT), Echo).serve_forever()
