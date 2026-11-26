#!/usr/bin/env python3
"""Exporter mínimo para o comparativo de escalabilidade do Raiji.

Sem tocar no código dos serviços, coleta:

  * sondas HTTP (health/latência/status) de cada serviço;
  * CPU/memória/rede por container (Docker Engine API via socket);
  * resultados do comparativo 1×N gravados por `run-scale.sh` (results/scale.json).

Expõe tudo em /metrics no formato Prometheus (texto), pronto para o scrape.
Somente biblioteca padrão do Python — nenhuma dependência extra.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import statistics
import threading
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor
import urllib.request
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.getenv("PORT", "9101"))
DOCKER_SOCKET = os.getenv("DOCKER_SOCKET", "/var/run/docker.sock")
CONTAINER_PREFIX = os.getenv("CONTAINER_PREFIX", "raiji")
RESULTS_FILE = os.getenv("RESULTS_FILE", "/results/scale.json")
STATS_TTL = float(os.getenv("STATS_TTL", "5"))
PROBE_TIMEOUT = float(os.getenv("PROBE_TIMEOUT", "2"))
RABBIT_MGMT_URL = os.getenv("RABBITMQ_MGMT_URL", "http://rabbitmq:15672").rstrip("/")
RABBITMQ_USER = os.getenv("RABBITMQ_USER", "raiji")
RABBITMQ_PASSWORD = os.getenv("RABBITMQ_PASSWORD", "raiji123")

DEFAULT_TARGETS = (
    "monolith=http://app:8080/actuator/health,"
    "chat=http://chat:3000/health,"
    "front=http://host.docker.internal:3000/,"
    "kong=http://kong:8000/realms/raiji,"
    "keycloak=http://keycloak:8080/realms/raiji"
)


def parse_targets(raw: str) -> list[tuple[str, str]]:
    targets: list[tuple[str, str]] = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        if "=" in item:
            name, url = item.split("=", 1)
        else:
            name, url = item, item
        targets.append((name.strip(), url.strip()))
    return targets


TARGETS = parse_targets(os.getenv("PROBE_TARGETS", DEFAULT_TARGETS))

_lock = threading.Lock()
_stats_cache: dict[str, object] = {"at": 0.0, "containers": [], "probes": [], "queues": []}


class UnixHTTPConnection(HTTPConnection):
    def __init__(self, path: str):
        super().__init__("localhost", timeout=5)
        self.unix_path = path

    def connect(self) -> None:  # type: ignore[override]
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(5)
        sock.connect(self.unix_path)
        self.sock = sock


def docker_get(path: str):
    conn = UnixHTTPConnection(DOCKER_SOCKET)
    try:
        conn.request("GET", path, headers={"Host": "docker"})
        response = conn.getresponse()
        payload = response.read()
        if response.status >= 400:
            return None
        return json.loads(payload or b"null")
    except Exception:
        return None
    finally:
        conn.close()


def _container_stat(container: dict) -> dict | None:
    name = (container.get("Names") or ["/?"])[0].lstrip("/")
    stats = docker_get(f"/containers/{container['Id']}/stats?stream=false")
    if not stats:
        return None
    cpu = stats.get("cpu_stats") or {}
    pre = stats.get("precpu_stats") or {}
    cpu_delta = float(cpu.get("cpu_usage", {}).get("total_usage", 0)) - float(
        pre.get("cpu_usage", {}).get("total_usage", 0)
    )
    system_delta = float(cpu.get("system_cpu_usage", 0)) - float(pre.get("system_cpu_usage", 0))
    online = cpu.get("online_cpus") or len(cpu.get("cpu_usage", {}).get("percpu_usage") or []) or 1
    percent = (cpu_delta / system_delta) * online * 100.0 if system_delta > 0 else 0.0
    mem = stats.get("memory_stats") or {}
    networks = (stats.get("networks") or {}).values()
    return {
        "name": name,
        "cpu_percent": max(0.0, percent),
        "memory_bytes": float(mem.get("usage", 0)),
        "memory_limit_bytes": float(mem.get("limit", 0)),
        "network_rx_bytes": float(sum(n.get("rx_bytes", 0) for n in networks)),
        "network_tx_bytes": float(sum(n.get("tx_bytes", 0) for n in networks)),
    }


def container_stats() -> list[dict]:
    containers = [
        c for c in (docker_get("/containers/json?all=0") or [])
        if not CONTAINER_PREFIX or (c.get("Names") or ["/?"])[0].lstrip("/").startswith(CONTAINER_PREFIX)
    ]
    if not containers:
        return []
    # stats em paralelo: com várias réplicas, a coleta sequencial passaria do scrape
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(_container_stat, containers))
    return [r for r in results if r]




def probe(target: tuple[str, str]) -> dict:
    name, url = target
    started = time.perf_counter()
    status = 0
    ok = 0
    try:
        with urllib.request.urlopen(url, timeout=PROBE_TIMEOUT) as response:
            status = response.status
            ok = 1 if 200 <= status < 400 else 0
    except urllib.error.HTTPError as exc:
        status = exc.code
        ok = 1 if 200 <= status < 400 else 0
    except Exception:
        ok = 0
    return {
        "service": name,
        "url": url,
        "up": ok,
        "status": status,
        "duration": time.perf_counter() - started,
    }


def rabbit_queues() -> list[dict]:
    """Filas via Management API (o plugin prometheus do RabbitMQ 4 só traz agregados)."""
    url = f"{RABBIT_MGMT_URL}/api/queues?columns=name,messages,messages_ready,messages_unacknowledged,consumers"
    token = base64.b64encode(f"{RABBITMQ_USER}:{RABBITMQ_PASSWORD}".encode()).decode()
    request = urllib.request.Request(url, headers={"Authorization": f"Basic {token}"})
    try:
        with urllib.request.urlopen(request, timeout=PROBE_TIMEOUT) as response:
            data = json.loads(response.read() or b"[]")
        return data if isinstance(data, list) else []
    except Exception:
        return []


def read_scale_results() -> list[dict]:
    try:
        with open(RESULTS_FILE, encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, list):
            return data
    except Exception:
        pass
    return []


def refresh(force: bool = False) -> None:
    now = time.time()
    with _lock:
        if not force and now - float(_stats_cache["at"]) < STATS_TTL:
            return
    # coleta FORA do lock: /metrics nunca fica bloqueado esperando o Docker
    with ThreadPoolExecutor(max_workers=8) as pool:
        containers_f = pool.submit(container_stats)
        probes_f = pool.submit(lambda: [probe(t) for t in TARGETS])
        queues_f = pool.submit(rabbit_queues)
        containers, probes, queues = containers_f.result(), probes_f.result(), queues_f.result()
    with _lock:
        _stats_cache["containers"] = containers
        _stats_cache["probes"] = probes
        _stats_cache["queues"] = queues
        _stats_cache["at"] = now


def fmt(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".")


def render_metrics() -> str:
    with _lock:
        containers = list(_stats_cache["containers"])  # type: ignore[arg-type]
        probes = list(_stats_cache["probes"])  # type: ignore[arg-type]

    lines: list[str] = [
        "# HELP raiji_exporter_build_info Exporter de sondas/containers do Raiji",
        "# TYPE raiji_exporter_build_info gauge",
        'raiji_exporter_build_info{version="1.0"} 1',
        "# HELP raiji_probe_up Serviço respondeu 2xx/3xx na última sonda",
        "# TYPE raiji_probe_up gauge",
    ]
    for p in probes:  # type: ignore[union-attr]
        labels = f'service="{p["service"]}",url="{p["url"]}"'  # type: ignore[index]
        lines.append(f"raiji_probe_up{{{labels}}} {p['up']}")  # type: ignore[index]
        lines.append(f"raiji_probe_status_code{{{labels}}} {p['status']}")  # type: ignore[index]
        lines.append(f"raiji_probe_duration_seconds{{{labels}}} {fmt(float(p['duration']))}")  # type: ignore[index]

    lines += [
        "# HELP raiji_container_cpu_percent Uso de CPU do container (%)",
        "# TYPE raiji_container_cpu_percent gauge",
        "# HELP raiji_container_memory_bytes Memória usada pelo container",
        "# TYPE raiji_container_memory_bytes gauge",
        "# HELP raiji_container_memory_limit_bytes Limite de memória do container",
        "# TYPE raiji_container_memory_limit_bytes gauge",
        "# HELP raiji_container_network_receive_bytes_total Bytes recebidos",
        "# TYPE raiji_container_network_receive_bytes_total counter",
        "# HELP raiji_container_network_transmit_bytes_total Bytes transmitidos",
        "# TYPE raiji_container_network_transmit_bytes_total counter",
    ]
    for c in containers:  # type: ignore[union-attr]
        labels = f'container="{c["name"]}"'  # type: ignore[index]
        lines.append(f"raiji_container_cpu_percent{{{labels}}} {fmt(float(c['cpu_percent']))}")  # type: ignore[index]
        lines.append(f"raiji_container_memory_bytes{{{labels}}} {fmt(float(c['memory_bytes']))}")  # type: ignore[index]
        lines.append(f"raiji_container_memory_limit_bytes{{{labels}}} {fmt(float(c['memory_limit_bytes']))}")  # type: ignore[index]
        lines.append(f"raiji_container_network_receive_bytes_total{{{labels}}} {fmt(float(c['network_rx_bytes']))}")  # type: ignore[index]
        lines.append(f"raiji_container_network_transmit_bytes_total{{{labels}}} {fmt(float(c['network_tx_bytes']))}")  # type: ignore[index]

    with _lock:
        queues = list(_stats_cache["queues"])  # type: ignore[arg-type]
    lines += [
        "# HELP raiji_rabbit_queue_messages Mensagens na fila (Management API)",
        "# TYPE raiji_rabbit_queue_messages gauge",
        "# HELP raiji_rabbit_queue_messages_ready Mensagens prontas",
        "# TYPE raiji_rabbit_queue_messages_ready gauge",
        "# HELP raiji_rabbit_queue_messages_unacked Mensagens não confirmadas",
        "# TYPE raiji_rabbit_queue_messages_unacked gauge",
        "# HELP raiji_rabbit_queue_consumers Consumidores da fila",
        "# TYPE raiji_rabbit_queue_consumers gauge",
    ]
    for q in queues:  # type: ignore[union-attr]
        name = q.get("name", "")  # type: ignore[union-attr]
        labels = f'queue="{name}"'
        lines.append(f"raiji_rabbit_queue_messages{{{labels}}} {fmt(float(q.get('messages', 0) or 0))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_messages_ready{{{labels}}} {fmt(float(q.get('messages_ready', 0) or 0))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_messages_unacked{{{labels}}} {fmt(float(q.get('messages_unacknowledged', 0) or 0))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_consumers{{{labels}}} {fmt(float(q.get('consumers', 0) or 0))}")  # type: ignore[union-attr]

    results = read_scale_results()
    lines += [
        "# HELP raiji_scale_rps Vazão medida no comparativo 1xN",
        "# TYPE raiji_scale_rps gauge",
        "# HELP raiji_scale_p50_ms Latência p50 (ms)",
        "# TYPE raiji_scale_p50_ms gauge",
        "# HELP raiji_scale_p95_ms Latência p95 (ms)",
        "# TYPE raiji_scale_p95_ms gauge",
        "# HELP raiji_scale_p99_ms Latência p99 (ms)",
        "# TYPE raiji_scale_p99_ms gauge",
        "# HELP raiji_scale_failures Falhas no comparativo",
        "# TYPE raiji_scale_failures gauge",
        "# HELP raiji_scale_efficiency Eficiência de escala: (RPS_N / N) / RPS_1",
        "# TYPE raiji_scale_efficiency gauge",
        "# HELP raiji_scale_timestamp_seconds Quando o resultado foi gravado (epoch)",
        "# TYPE raiji_scale_timestamp_seconds gauge",
    ]
    baseline = next((r for r in results if int(r.get("instances", 0)) == 1), None)
    base_rps = float(baseline["rps"]) if baseline and baseline.get("rps") else 0.0
    for r in results:
        instances = int(r.get("instances", 0))
        if instances <= 0:
            continue
        labels = f'instances="{instances}"'
        rps = float(r.get("rps", 0) or 0)
        lines.append(f"raiji_scale_rps{{{labels}}} {fmt(rps)}")
        lines.append(f"raiji_scale_p50_ms{{{labels}}} {fmt(float(r.get('p50_ms', 0) or 0))}")
        lines.append(f"raiji_scale_p95_ms{{{labels}}} {fmt(float(r.get('p95_ms', 0) or 0))}")
        lines.append(f"raiji_scale_p99_ms{{{labels}}} {fmt(float(r.get('p99_ms', 0) or 0))}")
        lines.append(f"raiji_scale_failures{{{labels}}} {fmt(float(r.get('failures', 0) or 0))}")
        efficiency = (rps / instances) / base_rps if base_rps > 0 else 0.0
        lines.append(f"raiji_scale_efficiency{{{labels}}} {fmt(efficiency)}")
        lines.append(f"raiji_scale_timestamp_seconds{{{labels}}} {fmt(float(r.get('timestamp', 0) or 0))}")
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path.startswith("/metrics"):
            body = render_metrics().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/health"):
            body = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        return


def refresh_loop() -> None:
    while True:
        try:
            refresh(force=True)
        except Exception as exc:  # nunca derruba o exporter
            print(f"refresh falhou: {exc}", flush=True)
        time.sleep(STATS_TTL)


if __name__ == "__main__":
    # A coleta roda em segundo plano: /metrics responde sempre com o último estado.
    threading.Thread(target=refresh_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"probe-exporter ouvindo em :{PORT} | targets={[t[0] for t in TARGETS]}", flush=True)
    server.serve_forever()
