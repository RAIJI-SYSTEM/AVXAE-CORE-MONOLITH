"""Locust — comparativo 1×N do AVXAE-CORE-MONOLITH.

Recebe as URLs das instâncias em ``RAIJI_SCALE_URLS`` (ex.:
``http://127.0.0.1:8000,http://127.0.0.1:8001``). Por padrão exercita
``/actuator/health`` (sem autenticação); para rotas protegidas, defina
``RAIJI_SCALE_TOKEN`` e ``RAIJI_SCALE_PATH``.
"""

from __future__ import annotations

import os
import random

from locust import HttpUser, between, task

URLS = [u.strip().rstrip("/") for u in os.getenv("RAIJI_SCALE_URLS", "http://127.0.0.1:8000").split(",") if u.strip()]
TOKEN = os.getenv("RAIJI_SCALE_TOKEN", "").strip()
EXTRA_PATH = os.getenv("RAIJI_SCALE_PATH", "").strip()


class ApiScaleUser(HttpUser):
    host = URLS[0] if URLS else ""
    wait_time = between(float(os.getenv("RAIJI_SCALE_WAIT_MIN", "0.05")), float(os.getenv("RAIJI_SCALE_WAIT_MAX", "0.2")))

    def on_start(self) -> None:
        self.base = random.choice(URLS)
        self.instance = self.base.rsplit(":", 1)[-1]
        self.headers = {"Authorization": f"Bearer {TOKEN}"} if TOKEN else {}

    @task(8)
    def health(self) -> None:
        self.client.get(f"{self.base}/actuator/health", name=f"api[{self.instance}] GET /actuator/health")

    @task(2)
    def me(self) -> None:
        if not TOKEN:
            return
        path = EXTRA_PATH or "/api/me"
        self.client.get(f"{self.base}{path}", name=f"api[{self.instance}] GET {path}", headers=self.headers)
