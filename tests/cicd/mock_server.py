"""Mock do stack (Kong + App + Keycloak) para testar o smoke-test.sh localmente.

Estado controlado por arquivos em MOCK_STATE_DIR:
  health.status   -> conteúdo do campo status ("UP"/"DOWN"); default UP
  token.mode      -> "ok" (aceita user/pass corretos) | "reject" (401 sempre)
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

STATE_DIR = os.environ.get("MOCK_STATE_DIR", "/tmp/raiji-mock-state")
REALM = "raiji"
CLIENT_ID = "raiji-api"
USER = os.environ.get("MOCK_USER", "teste")
PASSWORD = os.environ.get("MOCK_PASSWORD", "teste123")
FAKE_TOKEN = "mock-access-token-abc123"


def _state(name, default):
    try:
        with open(os.path.join(STATE_DIR, name), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return default


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silencioso
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/actuator/health":
            status = _state("health.status", "UP")
            self._send(200, {"status": status})
        elif self.path.startswith(f"/realms/{REALM}"):
            self._send(200, {"realm": REALM, "public_key": "xxx"})
        elif self.path == "/api/me":
            auth = self.headers.get("Authorization", "")
            if auth == f"Bearer {FAKE_TOKEN}":
                self._send(200, {"id": 1, "username": USER})
            else:
                self._send(401, {"error": "unauthorized"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == f"/realms/{REALM}/protocol/openid-connect/token":
            length = int(self.headers.get("Content-Length", 0))
            form = parse_qs(self.rfile.read(length).decode())
            mode = _state("token.mode", "ok")
            creds_ok = (
                form.get("username", [""])[0] == USER
                and form.get("password", [""])[0] == PASSWORD
                and form.get("client_id", [""])[0] == CLIENT_ID
                and form.get("grant_type", [""])[0] == "password"
            )
            if mode == "ok" and creds_ok:
                self._send(200, {"access_token": FAKE_TOKEN, "token_type": "Bearer", "expires_in": 300})
            else:
                self._send(401, {"error": "invalid_grant"})
        else:
            self._send(404, {"error": "not found"})


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    port = int(os.environ.get("MOCK_PORT", "18080"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock listening on 127.0.0.1:{port} (state: {STATE_DIR})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
