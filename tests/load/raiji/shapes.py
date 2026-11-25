"""Perfis de carga (LoadTestShape) selecionáveis por variável de ambiente.

Uso:
    LOAD_PROFILE=smoke   locust -f locustfile.py --headless
    LOAD_PROFILE=load    LOAD_USERS=200 LOAD_SPAWN=20 LOAD_HOLD=600 locust ...
    LOAD_PROFILE=stress  ...
    LOAD_PROFILE=spike   ...
    LOAD_PROFILE=soak    LOAD_USERS=50 LOAD_HOLD=3600 ...

Com `--class-picker` o Locust mostra os perfis na interface web.
"""

from __future__ import annotations

import os

from locust import LoadTestShape


def _f(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


def _i(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


class RaijiShape(LoadTestShape):
    """Perfil configurável por env (default: ramp suave de carga)."""

    profile = os.getenv("LOAD_PROFILE", "load").strip().lower()

    def tick(self):
        run = self.get_run_time()

        if self.profile == "smoke":
            # Carga constante: sobe até LOAD_USERS no ritmo de LOAD_SPAWN e
            # segura por LOAD_HOLD. (Antes o spawn era fixo em 2/s, o que fazia
            # 1200 usuários levarem ~10 min para entrar.)
            users = _i("LOAD_USERS", 1200)
            spawn = _i("LOAD_SPAWN", 120)
            hold = _f("LOAD_HOLD", 60)
            if run > hold:
                return None
            return users, spawn

        if self.profile == "spike":
            # Sobe de 0 a N em 10s, segura, e derruba: testa recuperação/fila.
            users = _i("LOAD_USERS", 400)
            spawn = _i("LOAD_SPAWN", 40)
            hold = _f("LOAD_HOLD", 120)
            if run > hold:
                return None
            return users, spawn

        if self.profile == "soak":
            # Carga constante por horas: vazamento de memória, pool de conexões.
            users = _i("LOAD_USERS", 50)
            spawn = _i("LOAD_SPAWN", 5)
            hold = _f("LOAD_HOLD", 3600)
            if run > hold:
                return None
            return users, spawn

        if self.profile == "stress":
            # Degraus de 50 em 50 até N (ou até falhar): acha o joelho da curva.
            start = _i("LOAD_START", 50)
            step = _i("LOAD_STEP", 50)
            step_time = _f("LOAD_STEP_TIME", 120)
            max_users = _i("LOAD_USERS", 800)
            level = int(run // step_time)
            users = min(start + level * step, max_users)
            if users >= max_users and run > step_time * ((max_users // step) + 2):
                return None
            return users, _i("LOAD_SPAWN", 20)

        # load (default): rampa até N, segura, e para.
        users = _i("LOAD_USERS", 100)
        spawn = _i("LOAD_SPAWN", 10)
        ramp = _f("LOAD_RAMP", 60)
        hold = _f("LOAD_HOLD", 360)
        if run > ramp + hold:
            return None
        return users, spawn


class SmokeShape(RaijiShape):
    profile = "smoke"


class StressShape(RaijiShape):
    profile = "stress"


class SpikeShape(RaijiShape):
    profile = "spike"


class SoakShape(RaijiShape):
    profile = "soak"
