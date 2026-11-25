"""Obtenção e cache de tokens do Keycloak (password grant).

Por que cachear: cada vu do Locust precisa de um Bearer token. Pedir um token
novo a cada requisição mediria o Keycloak, não o serviço sob teste. O cache é
compartilhado entre as greenlets do processo (o Locust roda em um único
processo por worker), então 1000 vu custam ~1 request de token por minuto.
"""

from __future__ import annotations

import base64
import csv
import json
import random
import time
from dataclasses import dataclass
from typing import Dict, List, Optional

from raiji import config


@dataclass(frozen=True)
class Credentials:
    username: str
    password: str
    roles: str = "PASSENGER"
    subject: str = ""

    @property
    def is_driver(self) -> bool:
        return "DRIVER" in self.roles.upper()


def _jwt_exp(token: str) -> float:
    """Lê o `exp` do JWT sem validar assinatura (só para saber quando renovar)."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        return float(data.get("exp", 0))
    except Exception:  # token opaco ou formato inesperado: renova sempre
        return 0.0


class TokenCache:
    """Cache {username: (access, refresh, exp)} com renovação antecipada e backoff."""

    def __init__(self) -> None:
        self._tokens: Dict[str, tuple[str, str, float]] = {}
        self._failures: int = 0
        self._blocked_until: float = 0.0

    def get(self, client, cred: Credentials) -> Optional[str]:
        """Devolve um token válido. `client` é uma requests.Session (ou HttpSession)."""
        now = time.time()
        cached = self._tokens.get(cred.username)
        if cached and cached[2] - config.TOKEN_REFRESH_MARGIN > now:
            return cached[0]

        if now < self._blocked_until:
            # Keycloak recusou credenciais há pouco: não insiste em loop.
            return cached[0] if cached else None

        body = self._request(
            client,
            cred,
            {
                "grant_type": "password",
                "username": cred.username,
                "password": cred.password,
                "scope": "openid",
            },
            name="auth: token (password grant)",
        )
        if body is None:
            return cached[0] if cached else None
        return self._store(cred.username, body)

    def refresh(self, client, cred: Credentials) -> Optional[str]:
        """Renova com o refresh_token (é o que o app faz a cada ~5 min)."""
        cached = self._tokens.get(cred.username)
        if not cached or not cached[1]:
            return self.get(client, cred)
        body = self._request(
            client,
            cred,
            {"grant_type": "refresh_token", "refresh_token": cached[1]},
            name="auth: token (refresh)",
        )
        if body is None:
            return self.get(client, cred)
        return self._store(cred.username, body)

    # ------------------------------------------------------------------ #
    def _request(self, client, cred: Credentials, data: dict, name: str) -> Optional[dict]:
        payload = {"client_id": config.CLIENT_ID, **data}
        if config.CLIENT_SECRET:
            payload["client_secret"] = config.CLIENT_SECRET

        # A mesma função serve ao Locust (HttpSession, que aceita name= e
        # catch_response=) e a scripts comuns (requests.Session). Só passamos os
        # extras do Locust quando o cliente é dele.
        is_locust = client.__class__.__module__.startswith("locust")
        extra = {"name": name, "catch_response": True} if is_locust else {}

        with client.post(
            config.GATEWAY + config.TOKEN_PATH,
            data=payload,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=config.HTTP_TIMEOUT,
            **extra,
        ) as resp:
            if resp.status_code != 200:
                self._failures += 1
                # 401 = credencial inválida; 5xx/429 = instabilidade: backoff curto.
                self._blocked_until = time.time() + (
                    30 if resp.status_code in (401, 400) else 5
                )
                return None
            self._blocked_until = 0.0
            self._failures = 0
            return resp.json()

    def _store(self, username: str, body: dict) -> str:
        token = body["access_token"]
        exp = time.time() + float(body.get("expires_in", 300))
        self._tokens[username] = (token, body.get("refresh_token", ""), exp)
        return token

    def has_session(self, username: str) -> bool:
        return username in self._tokens

    def invalidate(self, username: str) -> None:
        self._tokens.pop(username, None)


TOKENS = TokenCache()


class UserPool:
    """Credenciais de teste (users.csv do seed) + fallback para o usuário padrão."""

    def __init__(self, path=None) -> None:
        self._all: List[Credentials] = []
        self._drivers: List[Credentials] = []
        self._passengers: List[Credentials] = []
        self._load(path or config.USERS_FILE)

    def _load(self, path) -> None:
        try:
            with open(path, newline="", encoding="utf-8") as fh:
                for row in csv.DictReader(fh):
                    cred = Credentials(
                        username=row["username"].strip(),
                        password=row["password"].strip(),
                        roles=(row.get("roles") or "PASSENGER").strip(),
                        subject=(row.get("subject") or "").strip(),
                    )
                    self._all.append(cred)
                    (self._drivers if cred.is_driver else self._passengers).append(cred)
        except FileNotFoundError:
            pass
        if not self._all:
            fallback = Credentials(config.FALLBACK_USER, config.FALLBACK_PASSWORD)
            self._all.append(fallback)
            self._passengers.append(fallback)
            self._drivers.append(fallback)

    # ------------------------------------------------------------------ #
    def any(self) -> Credentials:
        return random.choice(self._all)

    def passenger(self) -> Credentials:
        return random.choice(self._passengers or self._all)

    def driver(self) -> Credentials:
        return random.choice(self._drivers or self._all)

    def all(self) -> List[Credentials]:
        return list(self._all)

    @property
    def size(self) -> int:
        return len(self._all)

    @property
    def drivers(self) -> int:
        return len(self._drivers)

    @property
    def has_real_drivers(self) -> bool:
        return any(c.is_driver for c in self._all)


POOL = UserPool()


def bearer(token: Optional[str]) -> dict:
    return {"Authorization": f"Bearer {token}"} if token else {}


def login(client, cred: Credentials) -> Optional[str]:
    return TOKENS.get(client, cred)
