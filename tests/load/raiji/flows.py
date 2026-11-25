"""Fluxos de negócio reutilizados pelas classes de usuário.

Cada função recebe o "user" do Locust (que já sabe montar URL, token e nome de
requisição) e devolve o JSON da resposta ou None. Os códigos de erro esperados
no domínio (409 = sem vaga, já reservado, etc.) NÃO contam como falha; erro de
verdade (401/403/5xx) conta, via `user.check_ok`.

Caches de módulo (por processo do Locust) evitam recriar veículo/conversa a cada
iteração. Com vários workers, a primeira chamada de cada worker reconcilia via
GET — os helpers são idempotentes de propósito.
"""

from __future__ import annotations

import hashlib
import random
from datetime import datetime, timedelta
from typing import Optional

from raiji import config

_vehicle_cache: dict[str, str] = {}
_group_cache: dict[str, list[str]] = {}
_ride_cache: dict[str, list[str]] = {}


# --------------------------------------------------------------------------- #
# Utilidades
# --------------------------------------------------------------------------- #
def iso_local(dt: datetime) -> str:
    """Formato que o monolito espera em LocalDateTime (hora de Brasília)."""
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


def departure_in(minutes: int) -> str:
    return iso_local(datetime.now() + timedelta(minutes=minutes))


def _digest(text: str, n: int) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:n].upper()


def plate_for(username: str) -> str:
    """Placa única e estável por usuário (máx. 7 caracteres, sem conflito)."""
    return "LT" + _digest(username, 5)


def city_pair() -> tuple[tuple[str, str], tuple[str, str]]:
    """Par origem/destino com coordenadas reais (para a busca por proximidade)."""
    return random.choice(config.CITIES), random.choice(config.CITIES)


COORDS = {
    "São Paulo": (-23.5505, -46.6333),
    "Campinas": (-22.9056, -47.0608),
    "Rio de Janeiro": (-22.9068, -43.1729),
    "Belo Horizonte": (-19.9167, -43.9345),
    "Curitiba": (-25.4284, -49.2733),
}


def place(city: str, address: Optional[str] = None) -> dict:
    lat, lng = COORDS.get(city, COORDS["São Paulo"])
    return {
        "latitude": round(lat + random.uniform(-0.01, 0.01), 6),
        "longitude": round(lng + random.uniform(-0.01, 0.01), 6),
        "address": address or f"Centro, {city}",
    }


def baggage(items: int = 1) -> list[dict]:
    out = []
    for _ in range(items):
        out.append(
            {
                "type": random.choice(["mala", "mochila", "sacola", "caixa"]),
                "quantity": random.randint(1, 2),
                # volume total (L x A x C x qtd) fica abaixo dos 300 L do veículo
                # e o peso abaixo do limite de carga da carona — evita 400 no ACEITE
                "lengthCm": random.randint(30, 50),
                "widthCm": random.randint(20, 30),
                "heightCm": random.randint(15, 25),
                "weightKg": round(random.uniform(1, 6), 2),
                "description": "Bagagem sintética (teste de carga)",
            }
        )
    return out


# --------------------------------------------------------------------------- #
# Veículo
# --------------------------------------------------------------------------- #
def ensure_vehicle(user) -> Optional[str]:
    """Devolve o id de um veículo do motorista, criando um na primeira vez."""
    cached = _vehicle_cache.get(user.cred.username)
    if cached:
        return cached

    resp = user.api("GET", "/vehicles", name="api: GET /vehicles")
    if resp.status_code == 200 and resp.json():
        _vehicle_cache[user.cred.username] = resp.json()[0]["id"]
        return resp.json()[0]["id"]
    if not user.check_ok(resp):
        return None

    body = {
        "plate": plate_for(user.cred.username),
        "type": "carro",
        "model": random.choice(["Onix", "HB20", "Argo", "Polo", "Corolla"]),
        "color": random.choice(["Prata", "Branco", "Preto", "Cinza"]),
        "year": random.randint(2015, 2024),
        "passengerSeats": 4,
        "cargoVolumeLiters": 300,
    }
    resp = user.api("POST", "/vehicles", name="api: POST /vehicles", ok=(201, 409), json=body)
    if resp.status_code == 201:
        vid = resp.json()["id"]
        _vehicle_cache[user.cred.username] = vid
        return vid
    if resp.status_code == 409:  # já existe (outro worker criou): reconcilia
        resp = user.api("GET", "/vehicles", name="api: GET /vehicles")
        if resp.status_code == 200 and resp.json():
            vid = resp.json()[0]["id"]
            _vehicle_cache[user.cred.username] = vid
            return vid
        return None
    user.check_ok(resp)
    return None


# --------------------------------------------------------------------------- #
# Carona
# --------------------------------------------------------------------------- #
def create_ride(user, vehicle_id: str, minutes_ahead: int = 30, seats: int = 3,
                paid: bool = True, with_places: bool = True,
                ride_type: str = "PASSENGER", cargo: bool = False,
                price_per_package: float | None = None,
                with_route: bool | None = None) -> Optional[dict]:
    """Publica uma carona. Retorna o corpo (com `id`) ou None se recusada.

    Regras do domínio que o payload respeita:
      * partida entre agora+5min e agora+3 meses (RN10);
      * `GET /api/rides/search` (mapa) só acha caronas com `route` — quando
        `with_route` não é passado, publicamos com rota para alimentar a busca;
      * CARGO exige `cargoDetails` e `totalSeats = 0`;
      * placa/veículo já validados por `ensure_vehicle`.
    """
    origin_city, origin_state = random.choice(config.CITIES)
    destination_city, destination_state = random.choice(config.CITIES)
    while destination_city == origin_city:
        destination_city, destination_state = random.choice(config.CITIES)

    if with_route is None:
        with_route = True

    body = {
        "vehicleId": vehicle_id,
        "type": ride_type,
        "originCity": origin_city,
        "originState": origin_state,
        "destinationCity": destination_city,
        "destinationState": destination_state,
        "departureDate": departure_in(minutes_ahead),
        "pricePerPassenger": round(random.uniform(10, 80), 2) if paid and ride_type != "CARGO" else None,
        "totalSeats": 0 if ride_type == "CARGO" else seats,
    }

    if cargo or ride_type in ("CARGO", "MIXED"):
        body["cargoDetails"] = {
            "maxWeightKg": 30,
            "maxLengthCm": 80,
            "maxWidthCm": 60,
            "maxHeightCm": 50,
            "observation": "Carga sintética (teste de carga)",
        }
        if price_per_package is not None:
            body["pricePerPackage"] = round(price_per_package, 2)

    if with_places:
        body["origin"] = place(origin_city)
        body["destination"] = place(destination_city)
        if with_route:
            # 3 pontos: origem -> meio -> destino (linha reta suficiente)
            o, d = body["origin"], body["destination"]
            body["route"] = [
                {"latitude": o["latitude"], "longitude": o["longitude"]},
                {
                    "latitude": round((o["latitude"] + d["latitude"]) / 2, 6),
                    "longitude": round((o["longitude"] + d["longitude"]) / 2, 6),
                },
                {"latitude": d["latitude"], "longitude": d["longitude"]},
            ]

    resp = user.api("POST", "/rides", name="api: POST /rides", ok=(201, 409), json=body)
    if resp.status_code == 201:
        ride = resp.json()
        _ride_cache.setdefault(user.cred.username, []).insert(0, ride["id"])
        del _ride_cache[user.cred.username][config.MAX_RIDES_PER_USER:]
        return ride
    return None


def search_rides(user) -> None:
    """Busca por cidade com filtros variados (endpoint mais quente do app)."""
    origin, _ = random.choice(config.CITIES)
    params = {
        "originCity": origin,
        "page": 0,
        "size": config.SEARCH_PAGE_SIZE,
        "sort": random.choice(
            ["departureDate,asc", "pricePerPassenger,asc", "departureDate,desc"]
        ),
    }
    if random.random() < 0.4:
        params["freeOnly"] = "true"
    elif random.random() < 0.5:
        params["maxPrice"] = random.choice([20, 40, 80])
    if random.random() < 0.3:
        params["minSeats"] = random.randint(1, 3)

    resp = user.api("GET", "/rides", name="api: GET /rides (busca)", params=params)
    user.check_ok(resp)


def search_along_route(user) -> None:
    """Busca por raio no mapa (origem, opcionalmente com destino)."""
    origin, destination = city_pair()
    lat, lng = COORDS[origin[0]]
    params = {
        "originLat": round(lat + random.uniform(-0.02, 0.02), 6),
        "originLng": round(lng + random.uniform(-0.02, 0.02), 6),
        "radiusMeters": random.choice([3000, 5000, 10000]),
    }
    if random.random() < 0.6:
        dlat, dlng = COORDS[destination[0]]
        params["destinationLat"] = dlat
        params["destinationLng"] = dlng
    resp = user.api("GET", "/rides/search", name="api: GET /rides/search", params=params)
    user.check_ok(resp)


def pick_ride_id(user, origin_city: Optional[str] = None) -> Optional[str]:
    """Pega um id de carona da busca (para detalhar/reservar)."""
    params = {"size": 5, "page": 0}
    if origin_city:
        params["originCity"] = origin_city
    resp = user.api("GET", "/rides", name="api: GET /rides (pool)", params=params)
    if resp.status_code != 200:
        user.check_ok(resp)
        return None
    content = resp.json().get("content") or []
    return random.choice(content)["id"] if content else None


def request_seat(user, ride_id: str) -> Optional[dict]:
    """Reserva uma vaga (exige role PASSENGER e bagagem declarada)."""
    body = {
        "passengerName": user.cred.username.split("@")[0],
        "baggage": baggage(random.randint(1, 2)),
    }
    # 409: sem vagas / duplicada / carona já partiu — comportamento esperado
    # 404: a carona saiu do ar entre a busca e a reserva — corrida normal
    resp = user.api(
        "POST",
        f"/rides/{ride_id}/requests",
        name="api: POST /rides/{id}/requests",
        ok=(201, 404, 409),
        json=body,
    )
    return resp.json() if resp.status_code == 201 else None


def accept_request(user, ride_id: str, request_id: str, payment: bool = False) -> bool:
    body = {"decision": "ACCEPT"}
    if payment and config.ENABLE_EXTERNAL:
        body["paymentIntentId"] = "pi_load_" + _digest(request_id, 12).lower()
    resp = user.api(
        "PATCH",
        f"/rides/{ride_id}/requests/{request_id}",
        name="api: PATCH /rides/{id}/requests/{rid} (aceite)",
        ok=(200, 404, 409),
        json=body,
    )
    return resp.status_code == 200


# --------------------------------------------------------------------------- #
# Chat
# --------------------------------------------------------------------------- #
def peer_credentials(user):
    """Outra identidade do pool (para cenários com dois atores)."""
    from raiji.auth import POOL

    for _ in range(6):
        other = POOL.any()
        if other.username != user.cred.username and other.subject:
            return other
    return None


def peer_subject(user) -> str:
    """keycloakId de outro usuário sintético (o chat identifica por ele)."""
    from raiji.auth import POOL

    for _ in range(6):
        other = POOL.any()
        if other.username != user.cred.username and other.subject:
            return other.subject
    return ""


def open_direct(user) -> Optional[str]:
    peer = peer_subject(user)
    if not peer:
        return None
    resp = user.chat(
        "POST",
        "/conversations/direct",
        name="chat: POST /conversations/direct",
        ok=(200, 201),
        json={"userId": peer, "role": "passenger", "myRole": "passenger"},
    )
    return resp.json()["_id"] if resp.status_code in (200, 201) else None


def ensure_group(user, members: int = 3) -> Optional[str]:
    """Reaproveita (ou cria) um grupo do usuário para o teste de chat."""
    cached = _group_cache.get(user.cred.username)
    if cached:
        return random.choice(cached)

    resp = user.chat("GET", "/groups", name="chat: GET /groups (pool)")
    if resp.status_code == 200 and resp.json():
        ids = [g["_id"] for g in resp.json()[:10]]
        _group_cache[user.cred.username] = ids
        return random.choice(ids)
    from raiji.auth import POOL

    member_ids = [c.subject for c in POOL.all() if c.subject and c.username != user.cred.username]
    body = {
        "name": f"Grupo carga {_digest(user.cred.username, 6)}",
        "memberIds": random.sample(member_ids, min(members, len(member_ids))),
    }
    resp = user.chat("POST", "/groups", name="chat: POST /groups", ok=(201,), json=body)
    if resp.status_code == 201:
        gid = resp.json()["_id"]
        _group_cache.setdefault(user.cred.username, []).append(gid)
        return gid
    return None


def send_message(user, conversation_id: str) -> None:
    text = random.choice(
        [
            "Cheguei ao ponto de encontro.",
            "Vou atrasar 5 minutos.",
            "Pode trazer uma mala pequena?",
            "Tudo certo para a partida.",
            "Obrigado pela carona!",
        ]
    )
    resp = user.chat(
        "POST",
        f"/conversations/{conversation_id}/messages",
        name="chat: POST /conversations/{id}/messages",
        json={"text": text},
    )
    user.check_ok(resp)


def list_messages(user, conversation_id: str) -> None:
    resp = user.chat(
        "GET",
        f"/conversations/{conversation_id}/messages",
        name="chat: GET /conversations/{id}/messages",
        params={"limit": random.choice([20, 50, 100])},
    )
    user.check_ok(resp)
