"""Testes de carga de mensageria (AMQP/RabbitMQ) com Locust.

Uso:
    ./run-messaging.sh deps     # instala pika/locust no .venv
    ./run-messaging.sh smoke    # 5 usuários virtuais, 20 s (ingestão + probe)
    ./run-messaging.sh load     # 30 usuários, rampa de 3/s, 120 s
    ./run-messaging.sh ride     # só eventos de carona (tag ride)
    ./run-messaging.sh user     # só eventos de usuário (tag user)
    ./run-messaging.sh egress   # só message.created (tag egress)
    ./run-messaging.sh probe    # só ida e volta no raiji.chat (tag probe)
    ./run-messaging.sh web      # interface em http://localhost:8089

Endereço: RAIJI_AMQP_URL (padrão amqp://raiji:raiji123@localhost:5672/%2f).

Esta suíte é separada da HTTP (`locustfile.py`): as métricas aparecem como
request_type="AMQP" nos mesmos relatórios CSV/HTML de `reports/`.
"""

from __future__ import annotations

import logging

from locust import events

from raiji import config

# Importadas para o Locust descobrir as classes de usuário (F401 intencional).
from raiji.users.messaging import (  # noqa: F401
    ChatExchangeProbe,
    ChatMessagePublisher,
    RideEventsPublisher,
    UserEventsPublisher,
)

log = logging.getLogger("raiji.messaging")
# pika loga cada conexão em INFO; no relatório isso só faz barulho.
logging.getLogger("pika").setLevel(logging.WARNING)


@events.init.add_listener
def _configure(environment, **kwargs):
    """Valida o ambiente e remove classes sem task para as tags escolhidas."""
    if not config.MESSAGING_ENABLED:
        log.warning("RAIJI_MESSAGING_ENABLED=false: suíte de mensageria desligada")

    log.info(
        "AMQP %s | exchanges: %s, %s, %s | probe: %s",
        config.AMQP_URL,
        config.RIDES_EXCHANGE,
        config.USERS_EXCHANGE,
        config.CHAT_EXCHANGE,
        config.AMQP_PROBE_ROUTING_KEY,
    )

    opts = getattr(environment, "parsed_options", None)
    include = list(getattr(environment, "tags", None) or getattr(opts, "tags", None) or [])
    exclude = list(getattr(environment, "exclude_tags", None) or getattr(opts, "exclude_tags", None) or [])
    if not include and not exclude:
        return

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
