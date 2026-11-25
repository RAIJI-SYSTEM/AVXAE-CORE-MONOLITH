#!/usr/bin/env python3
"""Preflight: confere se o ambiente está pronto para um teste de carga.

Roda em ~2 s e evita descobrir no meio da carga que faltou o chat, a role
DRIVER ou o front em modo produção.

    ./run.sh check      (ou)  python3 scripts/preflight.py
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from raiji import config  # noqa: E402
from raiji.auth import POOL, TOKENS  # noqa: E402

OK = "  [ok]  "
WARN = "  [!]   "
FAIL = "  [x]   "
problems = 0


def line(prefix: str, msg: str) -> None:
    global problems
    print(prefix + msg)
    if prefix == FAIL:
        problems += 1


def get(path: str, token: str | None = None, base: str | None = None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        return requests.get((base or config.GATEWAY) + path, headers=headers, timeout=10)
    except requests.RequestException as exc:
        return exc


def main() -> int:
    print(f"== alvo: Kong {config.GATEWAY} | Next {config.FRONT}\n")

    # 1) Kong + monolito
    r = get("/actuator/health")
    if isinstance(r, Exception):
        line(FAIL, f"Kong inacessível em {config.GATEWAY}: {r}")
        return 1
    body = r.json() if r.status_code == 200 else {}
    if body.get("status") == "UP":
        line(OK, f"monolito health: UP ({r.elapsed.total_seconds() * 1000:.0f} ms)")
    else:
        line(FAIL, f"monolito health: {r.status_code} {r.text[:120]}")

    # 2) Keycloak
    r = get(f"/realms/{config.REALM}/.well-known/openid-configuration")
    line(OK if r.status_code == 200 else FAIL, f"Keycloak discovery: HTTP {r.status_code}")

    # 3) front
    try:
        r = requests.get(config.FRONT + "/", timeout=10)
        dev = r.headers.get("x-nextjs-cache") is None and "next" in r.headers.get("x-powered-by", "")
        line(
            OK if r.status_code == 200 else FAIL,
            f"Next home: HTTP {r.status_code} ({r.elapsed.total_seconds() * 1000:.0f} ms)"
            + ("  [dev: números não valem, use npm run build && npm start]" if dev else ""),
        )
    except requests.RequestException as exc:
        line(WARN, f"Next inacessível em {config.FRONT}: {exc} (testes de front serão ignorados)")

    # 4) pool de usuários
    print()
    if config.USERS_FILE.exists():
        with open(config.USERS_FILE, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        drivers = [r for r in rows if "DRIVER" in (r.get("roles") or "").upper()]
        line(
            OK if drivers else FAIL,
            f"users.csv: {len(rows)} usuários ({len(drivers)} motoristas)",
        )
        if not drivers:
            line(FAIL, "nenhum motorista: rode ./run.sh seed")
    else:
        line(FAIL, f"users.csv ausente em {config.USERS_FILE}: rode ./run.sh seed")

    # 5) token real + serviços autenticados
    print()
    cred = POOL.any()
    session = requests.Session()
    token = TOKENS.get(session, cred)
    if not token:
        line(FAIL, f"login falhou para {cred.username}")
    else:
        line(OK, f"login: {cred.username} ({len(token)} bytes de token)")

        r = get("/api/me", token)
        line(OK if r.status_code == 200 else FAIL, f"GET /api/me: HTTP {r.status_code}")

        r = get(f"{config.CHAT_PREFIX}/users/me", token)
        line(
            OK if r.status_code == 200 else WARN,
            f"chat (MESSAGE-SERVICE): HTTP {r.status_code}"
            + ("" if r.status_code == 200 else " — suba com --profile chat"),
        )

        # 6) um motorista consegue listar veículos (valida role DRIVER)
        drv = POOL.driver()
        drv_token = TOKENS.get(session, drv)
        if drv_token:
            r = get("/api/vehicles", drv_token)
            line(
                OK if r.status_code == 200 else FAIL,
                f"role DRIVER em {drv.username}: GET /api/vehicles HTTP {r.status_code}",
            )

    # 7) massa existente (a busca precisa de caronas publicadas)
    print()
    r = get("/api/rides?size=1")
    if r.status_code == 200:
        total = r.json().get("totalElements", 0)
        line(
            OK if total > 0 else WARN,
            f"caronas publicadas: {total}"
            + ("" if total else " — a busca vai devolver vazio até a jornada popular o banco"),
        )

    print()
    if problems:
        print(f"== {problems} problema(s). Corrija antes de rodar carga.")
        return 1
    print("== ambiente pronto.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
