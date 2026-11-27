#!/usr/bin/env python3
"""Teste de resiliência: evento inválido vai para a DLQ e um evento válido é reprocessado."""
import json
import os
import sys
import time
from pathlib import Path

import requests

SLUG = sys.argv[1]
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "reports" / "drills"
OUT.mkdir(parents=True, exist_ok=True)
KONG = os.environ.get("KONG_URL", "http://localhost:8000")
MGMT = os.environ.get("RABBITMQ_MGMT_URL", "http://127.0.0.1:15672")
AUTH = ("raiji", "raiji123")
QUEUE = "chat.ride-events"
DLQ = f"{QUEUE}.dlq"
RIDE = f"dlq-{int(time.time())}"

steps = []


def token():
    r = requests.post(
        f"{KONG}/realms/raiji/protocol/openid-connect/token",
        data={"grant_type": "password", "client_id": "raiji-api", "username": "teste", "password": "teste123"},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()["access_token"]


def main():
    jwt = token()
    sub = requests.get(f"{KONG}/api/me", headers={"Authorization": f"Bearer {jwt}"}, timeout=10).json()["sub"]
    # Usa a API de gestão do RabbitMQ (porta 15672), já exposta no host.
    requests.delete(f"{MGMT}/api/queues/%2F/{DLQ}/contents", auth=AUTH, timeout=10)
    invalid = {"type": "ride.created", "rideId": RIDE, "driverId": sub, "passengerIds": "invalido"}
    requests.post(
        f"{MGMT}/api/exchanges/%2F/raiji.rides/publish",
        auth=AUTH,
        json={"properties": {}, "routing_key": "ride.created", "payload": json.dumps(invalid), "payload_encoding": "string"},
        timeout=10,
    ).raise_for_status()
    steps.append(("1. Evento inválido publicado", "OK"))

    dead = None
    for _ in range(30):
        response = requests.post(
            f"{MGMT}/api/queues/%2F/{DLQ}/get",
            auth=AUTH,
            json={"count": 10, "ackmode": "ack_requeue_false", "encoding": "auto"},
            timeout=10,
        )
        for item in response.json():
            payload = json.loads(item["payload"])
            if payload.get("rideId") == RIDE:
                dead = payload
                break
        if dead:
            break
        time.sleep(0.5)
    if not dead:
        raise RuntimeError("evento inválido não chegou à DLQ")
    steps.append(("2. Evento retido na DLQ", f"OK — {DLQ}"))

    valid = {"type": "ride.created", "rideId": RIDE, "driverId": sub, "passengerIds": []}
    requests.post(
        f"{MGMT}/api/exchanges/%2F/raiji.rides/publish",
        auth=AUTH,
        json={"properties": {}, "routing_key": "ride.created", "payload": json.dumps(valid), "payload_encoding": "string"},
        timeout=10,
    ).raise_for_status()
    steps.append(("3. Evento válido republicado", "OK"))

    ok = False
    for _ in range(30):
        r = requests.get(
            f"{KONG}/chat/conversations/by-ride/{RIDE}",
            headers={"Authorization": f"Bearer {jwt}"},
            timeout=10,
        )
        if r.status_code == 200:
            ok = True
            break
        time.sleep(0.5)
    if not ok:
        raise RuntimeError("evento válido não foi reprocessado pelo chat")
    steps.append(("4. Reprocessamento confirmado", "OK — grupo criado no chat"))

    rows = "".join(f"<tr><td>{a}</td><td class='ok'>{b}</td></tr>" for a, b in steps)
    html = f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Mensageria / DLQ — {SLUG}</title>
<style>body{{font-family:system-ui,Arial,sans-serif;margin:2rem;max-width:900px}}
table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #d1d5db;padding:.6rem;text-align:left}}
.ok{{color:#047857}}</style>
<p><a href="index.html">← Voltar ao índice</a></p>
<h1>Teste de Mensageria — DLQ e reprocessamento</h1>
<p><strong>Serviço:</strong> {SLUG}</p>
<table><tr><th>Etapa</th><th>Resultado</th></tr>{rows}</table>
<p>Evento de teste: <code>{RIDE}</code>. A DLQ usada foi <code>{DLQ}</code>; a mensagem inválida foi retirada e uma válida foi republicada no exchange <code>raiji.rides</code>.</p>
</html>"""
    (OUT / f"{SLUG}_dlq.html").write_text(html, encoding="utf-8")
    print(f"{SLUG}: DLQ + reprocessamento OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        rows = "".join(f"<tr><td>{a}</td><td>{b}</td></tr>" for a, b in steps)
        html = f"""<!doctype html><html><meta charset="utf-8"><title>DLQ — {SLUG}</title>
<p><a href="index.html">← Voltar</a></p><h1>Teste de Mensageria — falhou</h1>
<table>{rows}</table><p class="bad">{exc}</p></html>"""
        (OUT / f"{SLUG}_dlq.html").write_text(html, encoding="utf-8")
        print(f"{SLUG}: FALHOU — {exc}", file=sys.stderr)
        raise
