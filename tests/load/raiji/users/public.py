"""Carga anônima e de autenticação: o que acontece antes de qualquer login.

Mede o caminho de entrada do sistema: Kong (health/discovery do Keycloak) e o
endpoint de token (CPU de hash de senha no Keycloak). É a linha de base para
separar "o gateway está lento" de "o serviço X está lento".
"""

from __future__ import annotations

import random

from locust import HttpUser, tag, task
from locust import between

from raiji import config
from raiji.auth import POOL


class PublicEndpoints(HttpUser):
    """Anônimo: health check, discovery do realm e a home do Next."""

    weight = 1
    wait_time = between(1, 3)

    @tag("public", "read")
    @task(3)
    def health(self) -> None:
        with self.client.get(
            # /actuator/health é público; /api/actuator/health exige JWT (401)
            "/actuator/health",
            name="public: GET /actuator/health",
            timeout=config.HTTP_TIMEOUT,
            catch_response=True,
        ) as resp:
            if resp.status_code != 200 or resp.json().get("status") != "UP":
                resp.failure(f"health {resp.status_code}: {resp.text[:120]}")

    @tag("public", "read")
    @task(2)
    def realm_discovery(self) -> None:
        # O front busca o discovery a cada login: é o primeiro request do fluxo OIDC.
        resp = self.client.get(
            f"{config.GATEWAY}/realms/{config.REALM}/.well-known/openid-configuration",
            name="public: GET realm discovery",
            timeout=config.HTTP_TIMEOUT,
        )
        if resp.status_code != 200:
            resp.failure(f"discovery {resp.status_code}")

    @tag("public", "read", "front")
    @task(1)
    def front_home(self) -> None:
        # Em produção o Next serve HTML pré-renderizado; em `next dev` cada hit
        # compila. Ver README (seção "Como o front é medido").
        resp = self.client.get(
            config.FRONT + "/", name="front: GET /", timeout=config.HTTP_TIMEOUT
        )
        if resp.status_code != 200:
            resp.failure(f"home {resp.status_code}")


class TokenEndpoint(HttpUser):
    """Password grant: mede o Keycloak atrás do Kong (não use em loop no app real)."""

    weight = 1
    wait_time = between(2, 5)

    @tag("auth", "write")
    @task
    def password_grant(self) -> None:
        cred = POOL.any()
        data = {
            "grant_type": "password",
            "client_id": config.CLIENT_ID,
            "username": cred.username,
            "password": cred.password,
            "scope": "openid",
        }
        if config.CLIENT_SECRET:
            data["client_secret"] = config.CLIENT_SECRET

        with self.client.post(
            config.GATEWAY + config.TOKEN_PATH,
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            name="auth: POST token (password grant)",
            timeout=config.HTTP_TIMEOUT,
            catch_response=True,
        ) as resp:
            if resp.status_code != 200 or "access_token" not in resp.text:
                resp.failure(f"token {resp.status_code}: {resp.text[:160]}")

    @tag("auth", "read")
    @task(2)
    def refresh_grant(self) -> None:
        """Refresh token: é o que o app faz a cada ~5 min, não o password grant."""
        from raiji.auth import TOKENS

        cred = POOL.any()
        if not TOKENS.has_session(cred.username):
            TOKENS.get(self.client, cred)  # abre a sessão uma vez
            return
        # A requisição em si é registrada dentro do TokenCache com o nome
        # "auth: token (refresh)" — inclusive quando falha (backoff).
        TOKENS.refresh(self.client, cred)
