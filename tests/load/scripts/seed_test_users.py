#!/usr/bin/env python3
"""Cria os usuários sintéticos usados pelos testes de carga.

Por que existe: os fluxos de carga precisam de várias identidades. Usar sempre o
mesmo `teste/teste123` esconde problemas de cache por usuário, de lock por
registro e de consultas por `sub`. Também precisamos de motoristas com role
DRIVER, que o realm não traz por padrão.

O script é idempotente: usuários existentes são ignorados (e têm as roles
ajustadas). Ao criar um usuário, o listener do Keycloak publica `user.created`
no RabbitMQ — ou seja, o próprio seed já exercita a integração
Keycloak -> monolito/chat.

Uso:
    python3 scripts/seed_test_users.py                 # 50 passageiros + 20 motoristas
    SEED_PASSENGERS=10 SEED_DRIVERS=5 python3 scripts/seed_test_users.py

Saída:
    users.csv   (username,password,roles,subject) — lido pelo locustfile
"""

from __future__ import annotations

import csv
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from raiji import config  # noqa: E402

KC = config.KEYCLOAK_ADMIN
REALM = config.REALM
ADMIN_USER = os.getenv("KC_ADMIN_USER", "admin")
ADMIN_PASS = os.getenv("KC_ADMIN_PASSWORD", "admin")
PASSWORD = os.getenv("SEED_PASSWORD", "LoadTest#2026")
N_PASSENGERS = int(os.getenv("SEED_PASSENGERS", "50"))
N_DRIVERS = int(os.getenv("SEED_DRIVERS", "20"))
DOMAIN = os.getenv("SEED_DOMAIN", "raiji.test")
OUT = Path(os.getenv("RAIJI_LOAD_USERS_FILE", str(ROOT / "users.csv")))


def http(method: str, url: str, body=None, token=None, form=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = raw.decode(errors="replace")
        return exc.code, payload


def admin_token() -> str:
    status, body = http(
        "POST",
        f"{KC}/realms/master/protocol/openid-connect/token",
        {
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": ADMIN_USER,
            "password": ADMIN_PASS,
        },
        form=True,
    )
    if status != 200:
        sys.exit(
            f"[erro] login de admin no Keycloak falhou ({status}).\n"
            f"       Confira {KC} e KC_ADMIN_USER/KC_ADMIN_PASSWORD. Resposta: {body}"
        )
    return body["access_token"]


def find_user(token: str, username: str):
    q = urllib.parse.quote(username)
    status, body = http(
        "GET",
        f"{KC}/admin/realms/{REALM}/users?username={q}&exact=true",
        token=token,
    )
    if status != 200:
        return None
    for user in body or []:
        if user.get("username") == username:
            return user
    return None


def ensure_user(token: str, username: str, full_name: str, roles: list[str]):
    """Cria o usuário (se preciso) e garante as roles. Devolve o id."""
    user = find_user(token, username)
    if user is None:
        status, body = http(
            "POST",
            f"{KC}/admin/realms/{REALM}/users",
            {
                "username": username,
                "email": username,
                "emailVerified": True,
                "enabled": True,
                "firstName": full_name.split()[0],
                "lastName": full_name.split()[-1],
                "credentials": [
                    {"type": "password", "value": PASSWORD, "temporary": False}
                ],
                # role padrão do realm já inclui PASSENGER
                "realmRoles": [r for r in roles if r != "PASSENGER"],
                "attributes": {"load_test": ["true"]},
            },
            token=token,
        )
        if status not in (201, 204):
            print(f"  ! {username}: falha ao criar ({status}) {body}")
            return None
        user = find_user(token, username)
        if user is None:
            print(f"  ! {username}: criado mas não encontrado")
            return None
        created = True
    else:
        created = False

    uid = user["id"]
    for role in roles:
        status, role_body = http("GET", f"{KC}/admin/realms/{REALM}/roles/{role}", token=token)
        if status != 200:
            print(f"  ! role {role} não existe no realm")
            continue
        http(
            "POST",
            f"{KC}/admin/realms/{REALM}/users/{uid}/role-mappings/realm",
            [{"id": role_body["id"], "name": role_body["name"]}],
            token=token,
        )

    print(f"  {'+' if created else '='} {username:<34} {','.join(roles)}")
    return uid


def main() -> None:
    print(f"== Keycloak {KC} / realm {REALM}")
    token = admin_token()
    print(f"== criando {N_PASSENGERS} passageiros e {N_DRIVERS} motoristas")

    rows = []
    for i in range(1, N_PASSENGERS + 1):
        username = f"lt-pass-{i:03d}@{DOMAIN}"
        uid = ensure_user(token, username, f"Carga Passageiro {i}", ["PASSENGER"])
        if uid:
            rows.append((username, PASSWORD, "PASSENGER", uid))

    for i in range(1, N_DRIVERS + 1):
        username = f"lt-drv-{i:03d}@{DOMAIN}"
        uid = ensure_user(token, username, f"Carga Motorista {i}", ["PASSENGER", "DRIVER"])
        if uid:
            rows.append((username, PASSWORD, "PASSENGER,DRIVER", uid))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["username", "password", "roles", "subject"])
        writer.writerows(rows)

    print(f"\n== {len(rows)} usuários gravados em {OUT}")
    print("   senha:", PASSWORD)
    print("   dica: o monolito e o chat recebem user.created via RabbitMQ;")
    print("         se estiverem no ar, eles aparecem lá em segundos.")


if __name__ == "__main__":
    main()
