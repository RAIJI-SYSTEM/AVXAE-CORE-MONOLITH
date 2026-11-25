"""Caronas: navegação (leitura), consola do motorista e jornada completa.

A jornada é o teste "entre serviços": um motorista publica a carona, um
passageiro reserva, o motorista aceita, o monolito publica o evento no RabbitMQ,
o MESSAGE-SERVICE cria o grupo da viagem e os dois conversam nele.
"""

from __future__ import annotations

import random

from locust import between, tag, task

from raiji import config, flows
from raiji.auth import POOL, TOKENS
from raiji.base import RaijiBase


# --------------------------------------------------------------------------- #
# Leitura
# --------------------------------------------------------------------------- #
class RidesBrowse(RaijiBase):
    weight = 5
    wait_time = between(0.3, 1.5)

    @tag("read", "api")
    @task(5)
    def search_by_city(self) -> None:
        flows.search_rides(self)

    @tag("read", "api")
    @task(3)
    def search_by_map(self) -> None:
        flows.search_along_route(self)

    @tag("read", "api")
    @task(2)
    def ride_detail(self) -> None:
        ride_id = flows.pick_ride_id(self)
        if not ride_id:
            return
        self.api("GET", f"/rides/{ride_id}", name="api: GET /rides/{id}", ok=(200, 404))

    @tag("read", "api")
    @task(2)
    def price_preview(self) -> None:
        resp = self.api(
            "GET",
            "/rides/price-preview",
            name="api: GET /rides/price-preview",
            params={"price": round(random.uniform(5, 200), 2)},
        )
        self.check_ok(resp)

    @tag("read", "api")
    @task(2)
    def my_bookings(self) -> None:
        resp = self.api("GET", "/rides/bookings/mine", name="api: GET /rides/bookings/mine")
        self.check_ok(resp)

    @tag("read", "api")
    @task(2)
    def notifications(self) -> None:
        # O app faz isso a cada 30s no header, para os dois públicos.
        audience = random.choice(["PASSENGER", "DRIVER"])
        resp = self.api(
            "GET",
            "/notifications",
            name="api: GET /notifications",
            params={"audience": audience},
        )
        self.check_ok(resp)
        resp = self.api(
            "GET",
            "/notifications/unread-count",
            name="api: GET /notifications/unread-count",
            params={"audience": audience},
        )
        self.check_ok(resp)

    @tag("read", "api")
    @task(1)
    def rewards(self) -> None:
        resp = self.api("GET", "/rewards", name="api: GET /rewards")
        self.check_ok(resp)

    @tag("read", "api")
    @task(1)
    def profile(self) -> None:
        resp = self.api("GET", "/users/me", name="api: GET /users/me")
        self.check_ok(resp)
        resp = self.api("GET", "/users/me/contact", name="api: GET /users/me/contact")
        self.check_ok(resp)

    @tag("read", "api")
    @task(1)
    def my_intents(self) -> None:
        resp = self.api("GET", "/ride-intents/me", name="api: GET /ride-intents/me")
        self.check_ok(resp)

    @tag("read", "api")
    @task(1)
    def nearby_intents(self) -> None:
        lat, lng = flows.COORDS[random.choice(list(flows.COORDS))]
        resp = self.api(
            "GET",
            "/ride-intents/nearby",
            name="api: GET /ride-intents/nearby",
            params={
                "type": random.choice(["PASSENGER_REQUEST", "DRIVER_OFFER"]),
                "mode": random.choice(["ROUTE", "FREE"]),
                "latitude": lat,
                "longitude": lng,
                "radiusMeters": 5000,
            },
        )
        self.check_ok(resp)


# --------------------------------------------------------------------------- #
# Consola do motorista (o N+1 do app: lista viagens e, para cada uma, pedidos)
# --------------------------------------------------------------------------- #
class DriverConsole(RaijiBase):
    weight = 2
    wait_time = between(0.5, 2.0)

    def pick_credentials(self):
        return POOL.driver()

    @tag("read", "api", "driver")
    @task(4)
    def poll_jobs(self) -> None:
        resp = self.api(
            "GET",
            "/rides/mine",
            name="api: GET /rides/mine",
            params={"status": "PLANNED"},
        )
        if not self.check_ok(resp):
            return
        rides = resp.json()[:5]
        for ride in rides:
            self.api(
                "GET",
                f"/rides/{ride['id']}/requests",
                name="api: GET /rides/{id}/requests",
                ok=(200, 404),
            )
        # O app também pergunta as encomendas da viagem
        if rides and random.random() < 0.5:
            self.api(
                "GET",
                f"/rides/{rides[0]['id']}/deliveries",
                name="api: GET /rides/{id}/deliveries",
                ok=(200, 404),
            )

    @tag("read", "api", "driver")
    @task(2)
    def onboarding_state(self) -> None:
        self.check_ok(self.api("GET", "/vehicles", name="api: GET /vehicles"))
        self.check_ok(
            self.api("GET", "/rides/drivers/me/code", name="api: GET /rides/drivers/me/code")
        )
        self.check_ok(
            self.api("GET", "/drivers/me/payouts", name="api: GET /drivers/me/payouts")
        )

    @tag("read", "api", "driver")
    @task(2)
    def board_region(self) -> None:
        lat, lng = flows.COORDS[random.choice(list(flows.COORDS))]
        resp = self.api(
            "GET",
            "/board/region",
            name="api: GET /board/region",
            params={"lat": lat, "lng": lng},
        )
        self.check_ok(resp)

    @tag("read", "api", "driver")
    @task(1)
    def board_offered(self) -> None:
        self.check_ok(self.api("GET", "/board/offered", name="api: GET /board/offered"))

    @tag("read", "api", "driver")
    @task(1)
    def deliveries_driving(self) -> None:
        self.check_ok(
            self.api("GET", "/deliveries/driving", name="api: GET /deliveries/driving")
        )

    @tag("write", "api", "driver")
    @task(1)
    def publish_ride(self) -> None:
        """Publica uma carona nova (o fluxo de escrita mais comum do motorista)."""
        if not config.ENABLE_WRITES:
            return
        vehicle_id = flows.ensure_vehicle(self)
        if not vehicle_id:
            return
        ride = flows.create_ride(self, vehicle_id, minutes_ahead=random.randint(60, 4320))
        if ride and random.random() < 0.3:
            self.api(
                "POST",
                f"/rides/{ride['id']}/cancel",
                name="api: POST /rides/{id}/cancel",
                ok=(200, 201, 204, 409),
                json={"reason": "Teste de carga"},
            )


# --------------------------------------------------------------------------- #
# Jornada completa (integração monolito <-> chat via RabbitMQ)
# --------------------------------------------------------------------------- #
class RideJourney(RaijiBase):
    weight = 2
    wait_time = between(1.0, 3.0)

    def pick_credentials(self):
        return POOL.driver()

    def on_start(self) -> None:
        super().on_start()
        self.passenger = POOL.passenger()
        self.passenger_token: str | None = None

    # ------------------------------------------------------------------ #
    @tag("write", "api", "chat", "journey")
    @task
    def full_journey(self) -> None:
        if not config.ENABLE_WRITES:
            return

        vehicle_id = flows.ensure_vehicle(self)
        if not vehicle_id:
            return

        # 1) motorista publica a carona (partida daqui a 20-55 min: permite iniciar)
        ride = flows.create_ride(
            self, vehicle_id, minutes_ahead=random.randint(20, 55), seats=3
        )
        if not ride:
            return
        ride_id = ride["id"]

        # 2) passageiro entra (identidade diferente da do motorista)
        if self.passenger.username == self.cred.username:
            return
        self.passenger_token = TOKENS.get(self.client, self.passenger)
        if not self.passenger_token:
            return

        self.api_token(
            self.passenger_token,
            "GET",
            "/rides",
            name="api: GET /rides (busca do passageiro)",
            params={"originCity": ride["originCity"], "size": 5},
        )

        resp = self.api_token(
            self.passenger_token,
            "POST",
            f"/rides/{ride_id}/requests",
            name="api: POST /rides/{id}/requests (passageiro)",
            ok=(201, 404, 409),
            json={
                "passengerName": self.passenger.username.split("@")[0],
                "baggage": flows.baggage(random.randint(1, 2)),
            },
        )
        if resp.status_code != 201:
            # 409 = sem vaga/duplicada; 404 = carona saiu do ar. Não é falha.
            self.cancel(ride_id)
            return
        booking = resp.json()

        # 3) motorista vê os pedidos e aceita
        self.api("GET", f"/rides/{ride_id}/requests", name="api: GET /rides/{id}/requests")
        accepted = flows.accept_request(self, ride_id, booking["id"])
        if not accepted:
            self.cancel(ride_id)
            return

        # 4) o grupo do chat é criado de forma assíncrona (RabbitMQ).
        #    Uma passada de ~1s evita medir o 404 transitório como erro.
        self.think(0.8, 1.6)
        resp = self.api(
            "GET",
            f"/rides/{ride_id}/requests",
            name="api: GET /rides/{id}/requests (pós-aceite)",
        )
        self.check_ok(resp)

        if config.CHAT_ENABLED:
            conv_id = self.ride_group(ride_id)
            if conv_id:
                flows.send_message(self, conv_id)
                flows.list_messages(self, conv_id)

        # 5) ciclo de vida: inicia e finaliza (libera assentos e expira o grupo)
        resp = self.api(
            "POST",
            f"/rides/{ride_id}/start",
            name="api: POST /rides/{id}/start",
            ok=(200, 409),
        )
        if resp.status_code != 200:
            self.cancel(ride_id)
            return
        self.api(
            "POST",
            f"/rides/{ride_id}/finish",
            name="api: POST /rides/{id}/finish",
            ok=(200, 409),
        )

        self.api_token(
            self.passenger_token,
            "GET",
            "/rides/bookings/mine",
            name="api: GET /rides/bookings/mine (passageiro)",
        )

    # ------------------------------------------------------------------ #
    def ride_group(self, ride_id: str) -> str | None:
        """Consulta o grupo da carona; 404 é aceitável enquanto o evento não chega."""
        for _ in range(3):
            resp = self.chat(
                "GET",
                f"/conversations/by-ride/{ride_id}",
                name="chat: GET /conversations/by-ride/{rideId}",
                ok=(200, 404),
            )
            if resp.status_code == 200:
                return resp.json().get("_id")
            self.think(0.5, 1.0)
        return None

    def cancel(self, ride_id: str) -> None:
        self.api(
            "POST",
            f"/rides/{ride_id}/cancel",
            name="api: POST /rides/{id}/cancel",
            ok=(200, 201, 204, 409),
            json={"reason": "limpeza do teste de carga"},
        )
