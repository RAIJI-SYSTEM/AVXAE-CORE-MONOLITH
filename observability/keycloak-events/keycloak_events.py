#!/usr/bin/env python3
"""Exporta eventos e admin events do Keycloak como JSON lines para o stdout.

O Promtail coleta o stdout deste container e envia ao Loki. Assim o Grafana
consegue consultar login/logout/falhas e ações administrativas junto com os
logs dos três serviços.
"""
import json
import os
import time
from datetime import datetime, timezone

import requests

KEYCLOAK_URL = os.getenv("KEYCLOAK_URL", "http://keycloak:8080").rstrip("/")
REALM = os.getenv("KEYCLOAK_REALM", "raiji")
ADMIN_USER = os.getenv("KEYCLOAK_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("KEYCLOAK_ADMIN_PASSWORD", "admin")
POLL_SECONDS = int(os.getenv("POLL_SECONDS", "10"))
MAX_EVENTS = int(os.getenv("MAX_EVENTS", "200"))

seen_user: set[str] = set()
seen_admin: set[str] = set()
last_error_at = 0.0


def iso(ms) -> str | None:
    try:
        return datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).isoformat()
    except Exception:
        return None


def emit(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def error(message: str) -> None:
    global last_error_at
    now = time.time()
    if now - last_error_at < 60:
        return
    last_error_at = now
    emit({"service": "keycloak", "event": "exporter_error", "error": message})


def admin_token(session: requests.Session) -> str:
    response = session.post(
        f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": ADMIN_USER,
            "password": ADMIN_PASSWORD,
        },
        timeout=10,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def user_key(event: dict) -> str:
    return ":".join(
        str(event.get(k, ""))
        for k in ("time", "type", "userId", "sessionId", "error")
    )


def admin_key(event: dict) -> str:
    auth = event.get("authDetails") or {}
    return ":".join(
        str(event.get(k, ""))
        for k in ("time", "operationType", "resourcePath")
    ) + ":" + str(auth.get("userId", ""))


def export_user_events(session: requests.Session, headers: dict) -> None:
    response = session.get(
        f"{KEYCLOAK_URL}/admin/realms/{REALM}/events",
        params={"max": MAX_EVENTS},
        headers=headers,
        timeout=15,
    )
    response.raise_for_status()
    for event in sorted(response.json(), key=lambda e: e.get("time", 0)):
        key = user_key(event)
        if key in seen_user:
            continue
        seen_user.add(key)
        details = event.get("details") or {}
        emit({
            "service": "keycloak",
            "event": "keycloak_event",
            "kind": "user_event",
            "type": event.get("type"),
            "event_time_ms": event.get("time"),
            "at": iso(event.get("time")),
            "userId": event.get("userId"),
            "username": details.get("username"),
            "clientId": event.get("clientId"),
            "ipAddress": event.get("ipAddress"),
            "error": event.get("error"),
            "authMethod": details.get("auth_method"),
            "realmId": event.get("realmId"),
        })
    if len(seen_user) > MAX_EVENTS * 10:
        seen_user.clear()


def export_admin_events(session: requests.Session, headers: dict) -> None:
    response = session.get(
        f"{KEYCLOAK_URL}/admin/realms/{REALM}/admin-events",
        params={"max": MAX_EVENTS},
        headers=headers,
        timeout=15,
    )
    response.raise_for_status()
    for event in sorted(response.json(), key=lambda e: e.get("time", 0)):
        key = admin_key(event)
        if key in seen_admin:
            continue
        seen_admin.add(key)
        auth = event.get("authDetails") or {}
        actor_username = auth.get("username") or ("admin" if auth.get("userId") else None)
        emit({
            "service": "keycloak",
            "event": "keycloak_event",
            "kind": "admin_event",
            "type": event.get("operationType"),
            "event_time_ms": event.get("time"),
            "at": iso(event.get("time")),
            "resourcePath": event.get("resourcePath"),
            "actorId": auth.get("userId"),
            "actorUsername": actor_username,
            "actorIp": auth.get("ipAddress"),
            "clientId": auth.get("clientId"),
            "realmId": event.get("realmId"),
        })
    if len(seen_admin) > MAX_EVENTS * 10:
        seen_admin.clear()


def main() -> None:
    session = requests.Session()
    while True:
        try:
            token = admin_token(session)
            headers = {"Authorization": f"Bearer {token}"}
            export_user_events(session, headers)
            export_admin_events(session, headers)
        except Exception as exc:  # noqa: BLE001 - exporter nunca deve morrer
            error(f"{type(exc).__name__}: {exc}")
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
