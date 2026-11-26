"""Mensageria (AMQP) para os testes de carga com Locust.

O Locust é HTTP-first; aqui usamos um ``User`` puro + ``pika`` e publicamos as
métricas com ``events.request.fire(...)``. Assim os resultados aparecem nos
mesmos relatórios CSV/HTML do restante da suíte (``request_type="AMQP"``).

Métricas geradas
----------------
    publish ride.created / ride.synced / ride.finished   -> raiji.rides
    publish user.created / user.deleted                  -> raiji.users
    publish message.created                              -> raiji.chat
    consume raiji.chat (probe)                           -> ida e volta no exchange

O probe usa uma routing key própria (``RAIJI_AMQP_PROBE_ROUTING_KEY``, padrão
``loadtest.probe``) para NÃO poluir a fila do monolito. Para medir o caminho
real até o push, configure ``RAIJI_AMQP_PROBE_ROUTING_KEY=message.created``
(com o monolito no ar consumindo ``monolith.chat-messages``).

Limitações (por que o teste funcional continua sendo feito no Vitest/JUnit):
  - mede vazão/latência, não contrato (DLQ, retry, bindings);
  - a conexão é stateful: 1 conexão por usuário virtual;
  - não há status HTTP; falhas aparecem como exceção no relatório.
"""

from __future__ import annotations

import json
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from locust import events

from raiji import config

try:  # permite importar a suíte HTTP mesmo sem pika instalado
    import pika
except ImportError:  # pragma: no cover - só ocorre sem `run-messaging.sh deps`
    pika = None  # type: ignore[assignment]


def now_iso() -> str:
    """ISO 8601 com fuso (o schema dos eventos exige offset)."""
    return datetime.now(timezone.utc).isoformat()


def connect():
    """Abre uma conexão AMQP (uma por usuário virtual do Locust)."""
    if pika is None:  # pragma: no cover
        raise RuntimeError("pika não instalado: rode `./run-messaging.sh deps`")
    params = pika.URLParameters(config.AMQP_URL)
    params.heartbeat = 0  # runs curtos: sem heartbeat para não derrubar a conexão
    params.blocked_connection_timeout = 5
    params.socket_timeout = config.AMQP_TIMEOUT
    params.connection_attempts = 1
    params.client_properties = {"connection_name": "raiji-locust-messaging"}
    return pika.BlockingConnection(params)


def fire(name: str, started: float, response_length: int = 0, exception: BaseException | None = None) -> None:
    """Publica uma amostra AMQP no relatório do Locust."""
    events.request.fire(
        request_type="AMQP",
        name=name,
        response_time=(time.perf_counter() - started) * 1000,
        response_length=response_length,
        exception=exception,
    )


def ensure_topology(channel) -> None:
    """Declara os exchanges (idempotente) para a suíte rodar mesmo sem o chat no ar."""
    for exchange in (config.RIDES_EXCHANGE, config.USERS_EXCHANGE, config.CHAT_EXCHANGE):
        channel.exchange_declare(exchange=exchange, exchange_type="topic", durable=True)


def publish(channel, exchange: str, routing_key: str, payload: dict[str, Any], name: str,
            mandatory: bool = True) -> bool:
    """Publica JSON e registra a métrica. Devolve True quando confirmado."""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    started = time.perf_counter()
    exception: BaseException | None = None
    try:
        channel.basic_publish(
            exchange=exchange,
            routing_key=routing_key,
            body=body,
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
            mandatory=mandatory,
        )
    except BaseException as exc:  # noqa: BLE001 - queremos a exceção no relatório
        exception = exc
    fire(name, started, len(body), exception)
    return exception is None


# --------------------------------------------------------------------------- #
# Payloads (mesmo contrato de src/modules/**/…-events.ts do MESSAGE-SERVICE e
# do RideChatSync/ChatMessagePush do monólito)
# --------------------------------------------------------------------------- #

def ride_created(ride_id: str, driver_id: str, passengers: list[str], name: str | None = None) -> dict[str, Any]:
    return {
        "type": "ride.created",
        "rideId": ride_id,
        "driverId": driver_id,
        "passengerIds": passengers,
        **({"name": name} if name else {}),
    }


def ride_synced(ride_id: str, driver_id: str, driver_name: str, passengers: list[dict[str, Any]],
                origin: str, destination: str, status: str = "IN_PROGRESS") -> dict[str, Any]:
    return {
        "type": "ride.synced",
        "rideId": ride_id,
        "driverId": driver_id,
        "driverName": driver_name,
        "name": "Carona de carga",
        "origin": origin,
        "destination": destination,
        "status": status,
        "occurredAt": now_iso(),
        "passengers": passengers,
    }


def ride_finished(ride_id: str, cancelled: bool = False) -> dict[str, Any]:
    return {
        "type": "ride.cancelled" if cancelled else "ride.finished",
        "rideId": ride_id,
        "occurredAt": now_iso(),
    }


def ride_participant(ride_id: str, user_id: str, joined: bool = True) -> dict[str, Any]:
    return {
        "type": "ride.participant_joined" if joined else "ride.participant_left",
        "rideId": ride_id,
        "userId": user_id,
    }


def user_event(user_id: str, created: bool = True) -> dict[str, Any]:
    return {
        "type": "user.created" if created else "user.deleted",
        "userId": user_id,
    }


def chat_message(conversation_id: str, sender_id: str, title: str, preview: str,
                 recipient_ids: list[str]) -> dict[str, Any]:
    return {
        "conversationId": conversation_id,
        "senderId": sender_id,
        "title": title,
        "preview": preview,
        "recipientIds": recipient_ids,
    }


def new_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"
