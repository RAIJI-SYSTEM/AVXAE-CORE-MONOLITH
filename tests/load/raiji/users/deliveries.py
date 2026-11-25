"""Encomendas: mural (board), frete e a encomenda dentro de uma carona.

Inclui o fluxo entre dois atores: o passageiro pede espaço para uma encomenda
numa carona publicada, o motorista aceita e a encomenda nasce CONFIRMED. O
monolito notifica o passageiro e o chat recebe o evento do grupo da carona.
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta

from locust import between, tag, task

from raiji import config, flows
from raiji.auth import POOL, TOKENS
from raiji.base import RaijiBase


PACKAGE_MAX_KG = {"ENVELOPE": 1, "SMALL": 5, "MEDIUM": 15, "LARGE": 30}


def package_payload() -> dict:
    size = random.choice(list(PACKAGE_MAX_KG))
    # O monolito valida o peso contra o tamanho escolhido (400 se exceder).
    weight = round(random.uniform(0.2, min(5.0, PACKAGE_MAX_KG[size])), 2)
    return {
        "size": size,
        "description": "Encomenda sintética (teste de carga)",
        "weightKg": weight,
        "fragile": random.random() < 0.2,
        "declaredValue": round(random.uniform(20, 500), 2),
        "recipientName": "Destinatário Carga",
        "recipientPhone": "+5511999999999",
    }


class BoardBrowse(RaijiBase):
    """Mural e frete: /board/region e /freight-requests/nearby exigem DRIVER."""

    weight = 2
    wait_time = between(0.5, 2.0)

    def pick_credentials(self):
        return POOL.driver()

    @tag("read", "api", "delivery")
    @task(4)
    def region(self) -> None:
        lat, lng = flows.COORDS[random.choice(list(flows.COORDS))]
        resp = self.api(
            "GET",
            "/board/region",
            name="api: GET /board/region",
            params={"lat": lat, "lng": lng},
        )
        self.check_ok(resp)

    @tag("read", "api", "delivery")
    @task(2)
    def mine(self) -> None:
        self.check_ok(self.api("GET", "/board/mine", name="api: GET /board/mine"))

    @tag("read", "api", "delivery")
    @task(2)
    def nearby_freight(self) -> None:
        lat, lng = flows.COORDS[random.choice(list(flows.COORDS))]
        resp = self.api(
            "GET",
            "/freight-requests/nearby",
            name="api: GET /freight-requests/nearby",
            params={"lat": lat, "lng": lng, "radiusKm": 100},
        )
        self.check_ok(resp)

    @tag("read", "api", "delivery")
    @task(1)
    def my_deliveries(self) -> None:
        self.check_ok(self.api("GET", "/deliveries/mine", name="api: GET /deliveries/mine"))

    @tag("write", "api", "delivery")
    @task(1)
    def publish_board_request(self) -> None:
        """Publica um pedido no mural (SEAT ou PACKAGE)."""
        if not config.ENABLE_WRITES:
            return
        origin, destination = flows.city_pair()
        kind = random.choice(["PACKAGE", "SEAT"])
        body = {
            "kind": kind,
            "origin": {
                "city": origin[0],
                "state": origin[1],
                **flows.place(origin[0]),
            },
            "destination": {
                "city": destination[0],
                "state": destination[1],
                **flows.place(destination[0]),
            },
            "desiredAt": flows.departure_in(random.randint(90, 2880)),
            "notes": "Pedido sintético (teste de carga)",
        }
        if kind == "PACKAGE":
            body["pkg"] = package_payload()
        resp = self.api("POST", "/board", name="api: POST /board", json=body)
        if not self.check_ok(resp):
            return
        # Limpeza: 30% dos pedidos são cancelados logo em seguida
        if random.random() < 0.3:
            board_id = resp.json()["id"]
            self.api(
                "POST",
                f"/board/{board_id}/cancel",
                name="api: POST /board/{id}/cancel",
                ok=(200, 201, 204, 409),
            )


class DeliveryJourney(RaijiBase):
    """Encomenda pedida pelo passageiro numa carona do motorista."""

    weight = 1
    wait_time = between(2.0, 4.0)

    def pick_credentials(self):
        return POOL.driver()

    def on_start(self) -> None:
        super().on_start()
        self.sender = POOL.passenger()

    @tag("write", "api", "delivery", "journey")
    @task
    def request_and_accept(self) -> None:
        if not config.ENABLE_WRITES:
            return
        sender_token = TOKENS.get(self.client, self.sender)
        if not sender_token or self.sender.username == self.cred.username:
            return

        vehicle_id = flows.ensure_vehicle(self)
        if not vehicle_id:
            return
        # Encomenda exige carona CARGO/MIXED (a carga precisa de espaço declarado)
        ride = flows.create_ride(
            self,
            vehicle_id,
            minutes_ahead=random.randint(120, 4320),
            seats=2,
            paid=False,
            ride_type="MIXED",
            cargo=True,
            price_per_package=15.0,
        )
        if not ride:
            return
        ride_id = ride["id"]

        resp = self.api_token(
            sender_token,
            "POST",
            f"/rides/{ride_id}/deliveries",
            name="api: POST /rides/{id}/deliveries",
            ok=(201, 404, 409),
            json={
                "pkg": package_payload(),
                "senderName": self.sender.username.split("@")[0],
            },
        )
        if resp.status_code != 201:
            self.cancel(ride_id)
            return
        delivery_id = resp.json()["id"]

        self.api(
            "GET",
            f"/rides/{ride_id}/deliveries",
            name="api: GET /rides/{id}/deliveries",
        )

        resp = self.api(
            "PATCH",
            f"/rides/{ride_id}/deliveries/{delivery_id}",
            name="api: PATCH /rides/{id}/deliveries/{did} (aceite)",
            ok=(200, 404, 409),
            json={"decision": "ACCEPT"},
        )
        if resp.status_code != 200:
            self.cancel(ride_id)
            return

        self.api_token(
            sender_token,
            "GET",
            f"/deliveries/{delivery_id}",
            name="api: GET /deliveries/{id} (remetente)",
        )

        # limpeza
        self.api(
            "POST",
            f"/deliveries/{delivery_id}/cancel",
            name="api: POST /deliveries/{id}/cancel",
            ok=(200, 201, 204, 409),
            json={"reason": "limpeza do teste de carga"},
        )
        self.cancel(ride_id)

    def cancel(self, ride_id: str) -> None:
        self.api(
            "POST",
            f"/rides/{ride_id}/cancel",
            name="api: POST /rides/{id}/cancel",
            ok=(200, 201, 204, 409),
            json={"reason": "limpeza do teste de carga"},
        )
