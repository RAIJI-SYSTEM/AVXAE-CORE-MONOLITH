"""Usuários virtuais de mensageria (AMQP) para o Locust.

Classes
-------
RideEventsPublisher     simula o monolito publicando ciclo de vida de carona
UserEventsPublisher     simula o Keycloak publicando user.created/user.deleted
ChatMessagePublisher    simula o chat publicando message.created (push no monolito)
ChatExchangeProbe       publica e consome no raiji.chat para medir a ida e volta

Tags (use ``--tags`` ou ``./run-messaging.sh <comando>``):
    ingest  -> RideEventsPublisher, UserEventsPublisher
    egress  -> ChatMessagePublisher
    probe   -> ChatExchangeProbe
    ride / user / chat  -> recortes por domínio
"""

from __future__ import annotations

import random
import time
import uuid

from locust import User, between, tag, task

from raiji import config, messaging


class AmqpUser(User):
    """Base: uma conexão/canal por usuário virtual, com publisher confirms."""

    abstract = True

    def on_start(self) -> None:
        started = time.perf_counter()
        try:
            self.connection = messaging.connect()
            self.channel = self.connection.channel()
            self.channel.confirm_delivery()
            messaging.ensure_topology(self.channel)
            messaging.fire("AMQP connect", started)
        except BaseException as exc:  # noqa: BLE001
            messaging.fire("AMQP connect", started, exception=exc)
            raise

    def on_stop(self) -> None:
        connection = getattr(self, "connection", None)
        if connection is None:
            return
        try:
            connection.close()
        except BaseException:  # noqa: BLE001 - conexão já pode ter caído
            pass

    def publish(self, exchange: str, routing_key: str, payload: dict, name: str,
                mandatory: bool = True) -> bool:
        return messaging.publish(self.channel, exchange, routing_key, payload, name, mandatory)


class RideEventsPublisher(AmqpUser):
    """Ingestão: o que o monolito publica em ``raiji.rides``."""

    wait_time = between(0.5, 2.0)

    def on_start(self) -> None:
        super().on_start()
        self._new_ride()

    def _new_ride(self) -> None:
        self.ride_id = messaging.new_id("load-ride")
        self.driver_id = messaging.new_id("load-driver")
        self.driver_name = "Motorista de Carga"
        self.passengers = [
            {"userId": messaging.new_id("load-passenger"), "name": f"Passageiro {i}"}
            for i in range(random.randint(1, 3))
        ]
        city_from, _ = random.choice(config.CITIES)
        city_to, _ = random.choice([c for c in config.CITIES if c[0] != city_from])
        self.origin, self.destination = city_from, city_to
        self.publish(
            config.RIDES_EXCHANGE,
            "ride.created",
            messaging.ride_created(
                self.ride_id,
                self.driver_id,
                [p["userId"] for p in self.passengers],
                name=f"{city_from} → {city_to}",
            ),
            "publish ride.created",
        )

    @tag("amqp", "ingest", "ride")
    @task(3)
    def sync_ride(self) -> None:
        """Foto completa: o chat cria/atualiza o grupo e os papéis."""
        self.publish(
            config.RIDES_EXCHANGE,
            "ride.synced",
            messaging.ride_synced(
                self.ride_id, self.driver_id, self.driver_name, self.passengers,
                self.origin, self.destination, status="IN_PROGRESS",
            ),
            "publish ride.synced",
        )

    @tag("amqp", "ingest", "ride")
    @task(1)
    def participant_joined(self) -> None:
        self.publish(
            config.RIDES_EXCHANGE,
            "ride.participant_joined",
            messaging.ride_participant(self.ride_id, messaging.new_id("load-passenger")),
            "publish ride.participant_joined",
        )

    @tag("amqp", "ingest", "ride")
    @task(1)
    def finish_ride(self) -> None:
        """Termina a carona (o grupo expira em 3 dias) e começa outra."""
        self.publish(
            config.RIDES_EXCHANGE,
            "ride.finished",
            messaging.ride_finished(self.ride_id, cancelled=random.random() < 0.2),
            "publish ride.finished",
        )
        self._new_ride()


class UserEventsPublisher(AmqpUser):
    """Ingestão: o que o plugin do Keycloak publica em ``raiji.users``."""

    wait_time = between(1.0, 3.0)

    def on_start(self) -> None:
        super().on_start()
        self.user_id = messaging.new_id("load-user")
        self.publish(
            config.USERS_EXCHANGE, "user.created",
            messaging.user_event(self.user_id, created=True),
            "publish user.created",
        )

    @tag("amqp", "ingest", "user")
    @task(1)
    def recycle_user(self) -> None:
        """Remove e recria: exercita a cascata de exclusão do chat."""
        self.publish(
            config.USERS_EXCHANGE, "user.deleted",
            messaging.user_event(self.user_id, created=False),
            "publish user.deleted",
        )
        self.user_id = messaging.new_id("load-user")
        self.publish(
            config.USERS_EXCHANGE, "user.created",
            messaging.user_event(self.user_id, created=True),
            "publish user.created",
        )


class ChatMessagePublisher(AmqpUser):
    """Egresso: o que o chat publica em ``raiji.chat`` (o monolito consome e envia push).

    Sem o monolito no ar não há fila ligada a ``message.created``; por isso a
    publicação não usa ``mandatory`` (a mensagem é descartada pelo exchange).
    """

    wait_time = between(0.5, 2.0)

    def on_start(self) -> None:
        super().on_start()
        self.conversation_id = messaging.new_id("load-conversation")
        self.sender_id = messaging.new_id("load-sender")
        self.recipients = [messaging.new_id("load-recipient") for _ in range(2)]

    @tag("amqp", "egress", "chat")
    @task
    def message_created(self) -> None:
        self.publish(
            config.CHAT_EXCHANGE,
            "message.created",
            messaging.chat_message(
                self.conversation_id,
                self.sender_id,
                title="Conversa de carga",
                preview=f"Mensagem de carga {uuid.uuid4().hex[:8]}",
                recipient_ids=self.recipients,
            ),
            "publish message.created",
            mandatory=False,
        )


class ChatExchangeProbe(AmqpUser):
    """Mede a ida e volta no exchange ``raiji.chat`` (publica e consome).

    Usa uma routing key dedicada por padrão para não acordar o monolito.
    Para exercitar o caminho real: ``RAIJI_AMQP_PROBE_ROUTING_KEY=message.created``.
    """

    wait_time = between(0.2, 1.0)

    def on_start(self) -> None:
        super().on_start()
        declared = self.channel.queue_declare(queue="", exclusive=True, auto_delete=True)
        self.queue = declared.method.queue
        self.channel.queue_bind(
            queue=self.queue,
            exchange=config.CHAT_EXCHANGE,
            routing_key=config.AMQP_PROBE_ROUTING_KEY,
        )

    @tag("amqp", "probe")
    @task
    def roundtrip(self) -> None:
        nonce = uuid.uuid4().hex
        payload = {"probe": nonce, "sentAt": messaging.now_iso()}
        started = time.perf_counter()
        exception: BaseException | None = None
        body = b""

        try:
            self.publish(
                config.CHAT_EXCHANGE,
                config.AMQP_PROBE_ROUTING_KEY,
                payload,
                "publish probe",
                mandatory=True,
            )
            deadline = time.monotonic() + config.AMQP_PROBE_TIMEOUT
            while time.monotonic() < deadline:
                method, _, received = self.channel.basic_get(queue=self.queue, auto_ack=True)
                if method is None:
                    time.sleep(0.005)
                    continue
                body = received or b""
                if nonce.encode() in body:
                    break
                body = b""
            if not body:
                raise TimeoutError(
                    f"probe não voltou em {config.AMQP_PROBE_TIMEOUT:.1f}s "
                    f"(exchange={config.CHAT_EXCHANGE}, key={config.AMQP_PROBE_ROUTING_KEY})"
                )
        except BaseException as exc:  # noqa: BLE001
            exception = exc

        messaging.fire("consume raiji.chat (probe)", started, len(body), exception)
