"""Monólito (AVXAE-CORE-MONOLITH) — testes de carga com Locust.

Uso:
    ./run.sh check      # pré-voo (Kong, Keycloak, roles, dados)
    ./run.sh seed       # cria os usuários de carga no Keycloak
    ./run.sh smoke      # 5 usuários, 1 min
    ./run.sh load       # 100 usuários, rampa de 60 s + 6 min
    ./run.sh web        # interface em http://localhost:8089

Este arquivo é autocontido: `raiji/` traz config, auth, base e fluxos.
Nada do projeto original foi alterado.
"""

from __future__ import annotations

import logging

from locust import events

from raiji import config
from raiji.shapes import RaijiShape, SmokeShape, SoakShape, SpikeShape, StressShape
from raiji.users.public import PublicEndpoints, TokenEndpoint
from raiji.users.rides import RidesBrowse, DriverConsole, RideJourney
from raiji.users.deliveries import BoardBrowse, DeliveryJourney

log = logging.getLogger("raiji.load")


@events.init.add_listener
def _configure(environment, **kwargs):
    """Ajusta a suíte ao ambiente antes do teste começar."""
    write_only = {RideJourney, DeliveryJourney}
    if not config.ENABLE_WRITES and write_only:
        environment.user_classes = [
            c for c in environment.user_classes if c not in write_only
        ]
        log.warning("RAIJI_ENABLE_WRITES=false: fluxos de escrita desativados")

    # O Locust filtra *tasks* por --tags, mas instancia classes que ficaram sem
    # task nenhuma (milhares de "No tasks defined"). Removemos essas classes.
    opts = getattr(environment, "parsed_options", None)
    include = list(getattr(environment, "tags", None) or getattr(opts, "tags", None) or [])
    exclude = list(
        getattr(environment, "exclude_tags", None) or getattr(opts, "exclude_tags", None) or []
    )
    if include or exclude:
        def eligible(cls) -> bool:
            for fn in cls.tasks or []:
                tags = getattr(fn, "locust_tag_set", set())
                if exclude and tags & set(exclude):
                    continue
                if include and not (tags & set(include)):
                    continue
                return True
            return False

        kept = [c for c in environment.user_classes if eligible(c)]
        for cls in environment.user_classes:
            if cls not in kept:
                log.info("classe %s ignorada (nenhuma task com tags %s)", cls.__name__, include)
        environment.user_classes = kept
        if not kept:
            log.error("nenhuma classe atende às tags %s", include)

    from raiji.auth import POOL

    if POOL.size <= 1:
        log.warning("users.csv ausente: usando '%s'. Rode ./run.sh seed.", config.FALLBACK_USER)
    log.info(
        "alvo: Kong=%s | perfil=%s | %d usuários no pool (%d motoristas)",
        config.GATEWAY,
        __import__("os").getenv("LOAD_PROFILE", "load"),
        POOL.size,
        POOL.drivers,
    )
