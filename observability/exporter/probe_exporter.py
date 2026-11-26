#!/usr/bin/env python3
"""Exporter mínimo do Raiji — Prometheus + Grafana, sem tocar nos serviços.

Coleta:
  * sondas HTTP (UP/latência/status) dos serviços;
  * containers via Docker API: CPU, memória (% do limite), rede, PIDs,
    restarts, health, OOM, throttling e I/O;
  * RabbitMQ via Management API: filas (mensagens, prontas, não confirmadas,
    consumidores, utilização, memória) e visão geral (conexões, canais, taxas);
  * PostgreSQL via `docker exec psql`: conexões, tamanho, cache hit, deadlocks,
    consultas longas;
  * MongoDB via `docker exec mongosh`: conexões, opcounters, memória, tamanho;
  * Kong Admin API (`/status`): conexões;
  * resultados do comparativo 1×N (`results/scale.json`).

Somente biblioteca padrão do Python.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --------------------------------------------------------------------------- #
# Configuração
# --------------------------------------------------------------------------- #
PORT = int(os.getenv("PORT", "9101"))
DOCKER_SOCKET = os.getenv("DOCKER_SOCKET", "/var/run/docker.sock")
CONTAINER_PREFIX = os.getenv("CONTAINER_PREFIX", "raiji")
RESULTS_FILE = os.getenv("RESULTS_FILE", "/results/scale.json")
STATS_TTL = float(os.getenv("STATS_TTL", "5"))
PROBE_TIMEOUT = float(os.getenv("PROBE_TIMEOUT", "2"))

RABBIT_MGMT_URL = os.getenv("RABBITMQ_MGMT_URL", "http://rabbitmq:15672").rstrip("/")
RABBITMQ_USER = os.getenv("RABBITMQ_USER", "raiji")
RABBITMQ_PASSWORD = os.getenv("RABBITMQ_PASSWORD", "raiji123")

POSTGRES_CONTAINER = os.getenv("POSTGRES_CONTAINER", "raiji-postgres")
POSTGRES_USER = os.getenv("POSTGRES_USER", "raiji")
POSTGRES_DB = os.getenv("POSTGRES_DB", "raiji_app")

MONGO_CONTAINER = os.getenv("MONGO_CONTAINER", "raiji-chat-mongo")
MONGO_DB = os.getenv("MONGO_DB", "raiji")

KONG_STATUS_URL = os.getenv("KONG_STATUS_URL", "http://kong:8001/status")

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
_cache: dict[str, object] = {
    "at": 0.0, "containers": [], "probes": [], "queues": [], "overview": {},
    "pg": {}, "mongo": {}, "kong": {},
}


# --------------------------------------------------------------------------- #
# Docker API (socket unix)
# --------------------------------------------------------------------------- #
class UnixHTTPConnection(HTTPConnection):
    def __init__(self, path: str):
        super().__init__("localhost", timeout=8)
        self.unix_path = path

    def connect(self) -> None:  # type: ignore[override]
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(8)
        sock.connect(self.unix_path)
        self.sock = sock


def docker_request(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
    conn = UnixHTTPConnection(DOCKER_SOCKET)
    try:
        payload = json.dumps(body).encode() if body is not None else None
        headers = {"Host": "docker", "Content-Type": "application/json"} if body is not None else {"Host": "docker"}
        conn.request(method, path, body=payload, headers=headers)
        response = conn.getresponse()
        return response.status, response.read()
    except Exception:
        return 0, b""
    finally:
        conn.close()


def docker_get(path: str):
    status, payload = docker_request("GET", path)
    if status == 0 or status >= 400:
        return None
    try:
        return json.loads(payload or b"null")
    except Exception:
        return None


def _demux(raw: bytes) -> str:
    """Remove o cabeçalho de 8 bytes do stream multiplexado do Docker."""
    out = b""
    i = 0
    while i + 8 <= len(raw):
        stream = raw[i]
        size = int.from_bytes(raw[i + 4:i + 8], "big")
        chunk = raw[i + 8:i + 8 + size]
        i += 8 + size
        if stream == 1:
            out += chunk
    if not out and raw:
        out = raw
    return out.decode("utf-8", errors="replace").strip()


def docker_exec(container: str, cmd: list[str]) -> str | None:
    status, payload = docker_request(
        "POST", f"/containers/{container}/exec",
        {"AttachStdout": True, "AttachStderr": False, "Tty": False, "Cmd": cmd},
    )
    if status >= 400:
        return None
    try:
        exec_id = json.loads(payload)["Id"]
    except Exception:
        return None
    conn = UnixHTTPConnection(DOCKER_SOCKET)
    try:
        body = json.dumps({"Detach": False, "Tty": False}).encode()
        conn.request("POST", f"/exec/{exec_id}/start", body=body,
                     headers={"Host": "docker", "Content-Type": "application/json"})
        response = conn.getresponse()
        return _demux(response.read())
    except Exception:
        return None
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Containers
# --------------------------------------------------------------------------- #
def _container_data(container: dict) -> dict | None:
    name = (container.get("Names") or ["/?"])[0].lstrip("/")
    cid = container["Id"]
    stats = docker_get(f"/containers/{cid}/stats?stream=false")
    inspect = docker_get(f"/containers/{cid}/json") or {}
    if not stats:
        return None

    cpu = stats.get("cpu_stats") or {}
    pre = stats.get("precpu_stats") or {}
    cpu_delta = float(cpu.get("cpu_usage", {}).get("total_usage", 0)) - float(
        pre.get("cpu_usage", {}).get("total_usage", 0)
    )
    system_delta = float(cpu.get("system_cpu_usage", 0)) - float(pre.get("system_cpu_usage", 0))
    online = cpu.get("online_cpus") or len(cpu.get("cpu_usage", {}).get("percpu_usage") or []) or 1
    cpu_percent = (cpu_delta / system_delta) * online * 100.0 if system_delta > 0 else 0.0

    mem = stats.get("memory_stats") or {}
    mem_usage = float(mem.get("usage", 0))
    mem_limit = float(mem.get("limit", 0))
    mem_percent = (mem_usage / mem_limit * 100.0) if mem_limit > 0 else 0.0

    throttle = (cpu.get("throttling_data") or {})
    blkio = (stats.get("blkio_stats") or {}).get("io_service_bytes_recursive") or []
    read_bytes = sum(float(e.get("value", 0)) for e in blkio if e.get("op") == "read")
    write_bytes = sum(float(e.get("value", 0)) for e in blkio if e.get("op") == "write")
    networks = (stats.get("networks") or {}).values()

    state = (inspect.get("State") or {})
    health = ((state.get("Health") or {}).get("Status")) or ""
    healthy = 1.0 if (health == "healthy" or (not health and state.get("Running"))) else 0.0

    return {
        "name": name,
        "cpu_percent": max(0.0, cpu_percent),
        "memory_bytes": mem_usage,
        "memory_limit_bytes": mem_limit,
        "memory_percent": max(0.0, mem_percent),
        "pids": float((stats.get("pids_stats") or {}).get("current", 0) or 0),
        "network_rx_bytes": float(sum(n.get("rx_bytes", 0) for n in networks)),
        "network_tx_bytes": float(sum(n.get("tx_bytes", 0) for n in networks)),
        "restarts_total": float(inspect.get("RestartCount", 0) or 0),
        "oom_killed": 1.0 if state.get("OOMKilled") else 0.0,
        "healthy": healthy,
        "throttled_periods_total": float(throttle.get("throttled_periods", 0) or 0),
        "throttled_time_seconds_total": float(throttle.get("throttled_time", 0) or 0) / 1e9,
        "block_read_bytes_total": read_bytes,
        "block_write_bytes_total": write_bytes,
    }


def container_stats() -> list[dict]:
    containers = [
        c for c in (docker_get("/containers/json?all=0") or [])
        if not CONTAINER_PREFIX or (c.get("Names") or ["/?"])[0].lstrip("/").startswith(CONTAINER_PREFIX)
    ]
    if not containers:
        return []
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(_container_data, containers))
    return [r for r in results if r]


# --------------------------------------------------------------------------- #
# Sondas HTTP
# --------------------------------------------------------------------------- #
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
    return {"service": name, "url": url, "up": ok, "status": status,
            "duration": time.perf_counter() - started}


# --------------------------------------------------------------------------- #
# RabbitMQ (Management API)
# --------------------------------------------------------------------------- #
def fetch_json(url: str, auth: str | None = None) -> object | None:
    headers = {"Authorization": auth} if auth else {}
    try:
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=PROBE_TIMEOUT) as response:
            return json.loads(response.read() or b"null")
    except Exception:
        return None


def rabbit_auth() -> str:
    token = base64.b64encode(f"{RABBITMQ_USER}:{RABBITMQ_PASSWORD}".encode()).decode()
    return f"Basic {token}"


def rabbit_queues() -> list[dict]:
    data = fetch_json(
        f"{RABBIT_MGMT_URL}/api/queues?columns=name,messages,messages_ready,"
        "messages_unacknowledged,consumers,consumer_utilisation,state,memory",
        rabbit_auth(),
    )
    return data if isinstance(data, list) else []


def rabbit_overview() -> dict:
    data = fetch_json(f"{RABBIT_MGMT_URL}/api/overview", rabbit_auth())
    if not isinstance(data, dict):
        return {}
    totals = data.get("object_totals") or {}
    queue_totals = data.get("queue_totals") or {}
    stats = data.get("message_stats") or {}

    def rate(key: str) -> float:
        details = stats.get(key) or {}
        return float(details.get("rate", 0) or 0) if isinstance(details, dict) else 0.0

    return {
        "up": 1.0,
        "connections": float(totals.get("connections", 0) or 0),
        "channels": float(totals.get("channels", 0) or 0),
        "consumers": float(totals.get("consumers", 0) or 0),
        "queues": float(totals.get("queues", 0) or 0),
        "messages": float(queue_totals.get("messages", 0) or 0),
        "messages_ready": float(queue_totals.get("messages_ready", 0) or 0),
        "messages_unacknowledged": float(queue_totals.get("messages_unacknowledged", 0) or 0),
        "publish_rate": rate("publish_details"),
        "deliver_rate": rate("deliver_get_details"),
        "ack_rate": rate("ack_details"),
        "alarms": float(len(data.get("alarms") or [])),
    }


# --------------------------------------------------------------------------- #
# PostgreSQL / MongoDB / Kong
# --------------------------------------------------------------------------- #
def postgres_metrics() -> dict:
    sql = (
        "select count(*) from pg_stat_activity where datname='" + POSTGRES_DB + "';"
        "select pg_database_size('" + POSTGRES_DB + "');"
        "select coalesce(round(sum(blks_hit)*100.0/nullif(sum(blks_hit+blks_read),0),2),0) "
        "from pg_stat_database where datname='" + POSTGRES_DB + "';"
        "select coalesce(sum(deadlocks),0) from pg_stat_database where datname='" + POSTGRES_DB + "';"
        "select count(*) from pg_stat_activity where datname='" + POSTGRES_DB + "' "
        "and state='active' and now()-query_start > interval '5 seconds';"
        "select coalesce(max(extract(epoch from now()-query_start)),0) from pg_stat_activity "
        "where datname='" + POSTGRES_DB + "' and state='active';"
    )
    out = docker_exec(POSTGRES_CONTAINER, ["psql", "-U", POSTGRES_USER, "-d", POSTGRES_DB, "-tA", "-c", sql])
    if out is None:
        return {"up": 0.0}
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    if len(lines) < 6:
        return {"up": 0.0}
    try:
        return {
            "up": 1.0,
            "connections": float(lines[0]),
            "size_bytes": float(lines[1]),
            "cache_hit_ratio": float(lines[2]),
            "deadlocks_total": float(lines[3]),
            "long_queries": float(lines[4]),
            "max_query_seconds": float(lines[5]),
        }
    except ValueError:
        return {"up": 0.0}


def mongo_metrics() -> dict:
    js = (
        "const s=db.serverStatus(); const st=db.stats();"
        "print(JSON.stringify({conn:s.connections.current, q:s.opcounters.query,"
        " i:s.opcounters.insert, u:s.opcounters.update, d:s.opcounters.delete,"
        " c:s.opcounters.command, up:s.uptime, mem:s.mem.resident, size:st.dataSize}));"
    )
    out = docker_exec(MONGO_CONTAINER, ["mongosh", MONGO_DB, "--quiet", "--eval", js])
    if not out:
        return {"up": 0.0}
    try:
        data = json.loads(out.splitlines()[-1])
    except Exception:
        return {"up": 0.0}
    return {
        "up": 1.0,
        "connections": num(data.get("conn")),
        "queries_total": num(data.get("q")),
        "inserts_total": num(data.get("i")),
        "updates_total": num(data.get("u")),
        "deletes_total": num(data.get("d")),
        "commands_total": num(data.get("c")),
        "uptime_seconds": num(data.get("up")),
        "resident_memory_bytes": num(data.get("mem")) * 1024 * 1024,
        "db_size_bytes": num(data.get("size")),
    }


def kong_metrics() -> dict:
    data = fetch_json(KONG_STATUS_URL)
    if not isinstance(data, dict):
        return {"up": 0.0}
    server = data.get("server") or {}
    memory = data.get("memory") or {}
    workers = memory.get("workers_lua_vms") or []
    return {
        "up": 1.0,
        "connections_active": float(server.get("connections_active", 0) or 0),
        "connections_accepted_total": float(server.get("connections_accepted", 0) or 0),
        "connections_handled_total": float(server.get("connections_handled", 0) or 0),
        "workers": float(len(workers)),
    }


# --------------------------------------------------------------------------- #
# Comparativo 1×N
# --------------------------------------------------------------------------- #
def read_scale_results() -> list[dict]:
    try:
        with open(RESULTS_FILE, encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, list):
            return data
    except Exception:
        pass
    return []


# --------------------------------------------------------------------------- #
# Coleta e renderização
# --------------------------------------------------------------------------- #
def refresh(force: bool = False) -> None:
    now = time.time()
    with _lock:
        if not force and now - float(_cache["at"]) < STATS_TTL:
            return
    def safe(fn, default):
        try:
            return fn()
        except Exception as exc:  # um coletor com erro não derruba os demais
            print(f"coletor {getattr(fn, '__name__', '?')} falhou: {exc}", flush=True)
            return default

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [
            pool.submit(safe, container_stats, []),
            pool.submit(safe, lambda: [probe(t) for t in TARGETS], []),
            pool.submit(safe, rabbit_queues, []),
            pool.submit(safe, rabbit_overview, {}),
            pool.submit(safe, postgres_metrics, {"up": 0.0}),
            pool.submit(safe, mongo_metrics, {"up": 0.0}),
            pool.submit(safe, kong_metrics, {"up": 0.0}),
        ]
        containers, probes, queues, overview, pg, mongo, kong = [f.result() for f in futures]
    with _lock:
        _cache.update({"at": now, "containers": containers, "probes": probes, "queues": queues,
                       "overview": overview, "pg": pg, "mongo": mongo, "kong": kong})


def num(value) -> float:
    """Converte int/float/string e os 'Long' do mongosh ({high, low})."""
    if isinstance(value, dict):
        value = value.get("low", value.get("high", 0))
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def fmt(value: float) -> str:
    return f"{float(value):.6f}".rstrip("0").rstrip(".")


def render_metrics() -> str:
    with _lock:
        probes = list(_cache["probes"])  # type: ignore[arg-type]
        containers = list(_cache["containers"])  # type: ignore[arg-type]
        queues = list(_cache["queues"])  # type: ignore[arg-type]
        overview = dict(_cache["overview"])  # type: ignore[arg-type]
        pg = dict(_cache["pg"])  # type: ignore[arg-type]
        mongo = dict(_cache["mongo"])  # type: ignore[arg-type]
        kong = dict(_cache["kong"])  # type: ignore[arg-type]

    lines: list[str] = [
        "# HELP raiji_exporter_build_info Exporter de sondas/containers do Raiji",
        "# TYPE raiji_exporter_build_info gauge",
        'raiji_exporter_build_info{version="2.0"} 1',
        "# HELP raiji_probe_up Serviço respondeu 2xx/3xx na última sonda",
        "# TYPE raiji_probe_up gauge",
    ]
    for p in probes:  # type: ignore[union-attr]
        labels = f'service="{p["service"]}",url="{p["url"]}"'  # type: ignore[index]
        lines.append(f"raiji_probe_up{{{labels}}} {p['up']}")  # type: ignore[index]
        lines.append(f"raiji_probe_status_code{{{labels}}} {p['status']}")  # type: ignore[index]
        lines.append(f"raiji_probe_duration_seconds{{{labels}}} {fmt(p['duration'])}")  # type: ignore[index]

    lines += [
        "# HELP raiji_container_cpu_percent CPU do container (%)",
        "# TYPE raiji_container_cpu_percent gauge",
        "# HELP raiji_container_memory_bytes Memória usada",
        "# TYPE raiji_container_memory_bytes gauge",
        "# HELP raiji_container_memory_limit_bytes Limite de memória",
        "# TYPE raiji_container_memory_limit_bytes gauge",
        "# HELP raiji_container_memory_percent Memória usada / limite (%)",
        "# TYPE raiji_container_memory_percent gauge",
        "# HELP raiji_container_pids Processos no container",
        "# TYPE raiji_container_pids gauge",
        "# HELP raiji_container_restarts_total Reinícios do container",
        "# TYPE raiji_container_restarts_total counter",
        "# HELP raiji_container_oom_killed OOM kill (1 = sim)",
        "# TYPE raiji_container_oom_killed gauge",
        "# HELP raiji_container_healthy Healthcheck (1 = healthy/running)",
        "# TYPE raiji_container_healthy gauge",
        "# HELP raiji_container_cpu_throttled_periods_total Períodos com throttling",
        "# TYPE raiji_container_cpu_throttled_periods_total counter",
        "# HELP raiji_container_cpu_throttled_time_seconds_total Tempo throttled",
        "# TYPE raiji_container_cpu_throttled_time_seconds_total counter",
        "# HELP raiji_container_block_read_bytes_total Leitura de disco",
        "# TYPE raiji_container_block_read_bytes_total counter",
        "# HELP raiji_container_block_write_bytes_total Escrita de disco",
        "# TYPE raiji_container_block_write_bytes_total counter",
        "# HELP raiji_container_network_receive_bytes_total Rede recebida",
        "# TYPE raiji_container_network_receive_bytes_total counter",
        "# HELP raiji_container_network_transmit_bytes_total Rede transmitida",
        "# TYPE raiji_container_network_transmit_bytes_total counter",
    ]
    for c in containers:  # type: ignore[union-attr]
        labels = f'container="{c["name"]}"'  # type: ignore[index]
        for metric, key in [
            ("raiji_container_cpu_percent", "cpu_percent"),
            ("raiji_container_memory_bytes", "memory_bytes"),
            ("raiji_container_memory_limit_bytes", "memory_limit_bytes"),
            ("raiji_container_memory_percent", "memory_percent"),
            ("raiji_container_pids", "pids"),
            ("raiji_container_restarts_total", "restarts_total"),
            ("raiji_container_oom_killed", "oom_killed"),
            ("raiji_container_healthy", "healthy"),
            ("raiji_container_cpu_throttled_periods_total", "throttled_periods_total"),
            ("raiji_container_cpu_throttled_time_seconds_total", "throttled_time_seconds_total"),
            ("raiji_container_block_read_bytes_total", "block_read_bytes_total"),
            ("raiji_container_block_write_bytes_total", "block_write_bytes_total"),
            ("raiji_container_network_receive_bytes_total", "network_rx_bytes"),
            ("raiji_container_network_transmit_bytes_total", "network_tx_bytes"),
        ]:
            lines.append(f"{metric}{{{labels}}} {fmt(c[key])}")  # type: ignore[index]

    lines += [
        "# HELP raiji_rabbit_up Management API respondeu",
        "# TYPE raiji_rabbit_up gauge",
        "# HELP raiji_rabbit_connections Conexões abertas",
        "# TYPE raiji_rabbit_connections gauge",
        "# HELP raiji_rabbit_channels Canais",
        "# TYPE raiji_rabbit_channels gauge",
        "# HELP raiji_rabbit_consumers Consumidores",
        "# TYPE raiji_rabbit_consumers gauge",
        "# HELP raiji_rabbit_queues Filas",
        "# TYPE raiji_rabbit_queues gauge",
        "# HELP raiji_rabbit_messages Mensagens no broker",
        "# TYPE raiji_rabbit_messages gauge",
        "# HELP raiji_rabbit_messages_ready Mensagens prontas",
        "# TYPE raiji_rabbit_messages_ready gauge",
        "# HELP raiji_rabbit_messages_unacknowledged Mensagens não confirmadas",
        "# TYPE raiji_rabbit_messages_unacknowledged gauge",
        "# HELP raiji_rabbit_publish_rate Publicações/s",
        "# TYPE raiji_rabbit_publish_rate gauge",
        "# HELP raiji_rabbit_deliver_rate Entregas/s",
        "# TYPE raiji_rabbit_deliver_rate gauge",
        "# HELP raiji_rabbit_ack_rate Confirmações/s",
        "# TYPE raiji_rabbit_ack_rate gauge",
        "# HELP raiji_rabbit_alarms Alarmes ativos",
        "# TYPE raiji_rabbit_alarms gauge",
    ]
    if overview:
        for key in ["connections", "channels", "consumers", "queues", "messages", "messages_ready",
                    "messages_unacknowledged", "publish_rate", "deliver_rate", "ack_rate", "alarms"]:
            lines.append(f"raiji_rabbit_{key} {fmt(overview.get(key, 0))}")
        lines.append(f"raiji_rabbit_up {fmt(overview.get('up', 0))}")
    else:
        lines.append("raiji_rabbit_up 0")

    lines += [
        "# HELP raiji_rabbit_queue_messages Mensagens na fila",
        "# TYPE raiji_rabbit_queue_messages gauge",
        "# HELP raiji_rabbit_queue_messages_ready Mensagens prontas",
        "# TYPE raiji_rabbit_queue_messages_ready gauge",
        "# HELP raiji_rabbit_queue_messages_unacked Mensagens não confirmadas",
        "# TYPE raiji_rabbit_queue_messages_unacked gauge",
        "# HELP raiji_rabbit_queue_consumers Consumidores",
        "# TYPE raiji_rabbit_queue_consumers gauge",
        "# HELP raiji_rabbit_queue_consumer_utilisation Utilização dos consumidores (0-1)",
        "# TYPE raiji_rabbit_queue_consumer_utilisation gauge",
        "# HELP raiji_rabbit_queue_memory_bytes Memória da fila",
        "# TYPE raiji_rabbit_queue_memory_bytes gauge",
    ]
    for q in queues:  # type: ignore[union-attr]
        name = q.get("name", "")  # type: ignore[union-attr]
        labels = f'queue="{name}"'
        lines.append(f"raiji_rabbit_queue_messages{{{labels}}} {fmt(num(q.get('messages')))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_messages_ready{{{labels}}} {fmt(num(q.get('messages_ready')))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_messages_unacked{{{labels}}} {fmt(num(q.get('messages_unacknowledged')))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_consumers{{{labels}}} {fmt(num(q.get('consumers')))}")  # type: ignore[union-attr]
        utilisation = q.get("consumer_utilisation")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_consumer_utilisation{{{labels}}} {fmt(num(utilisation))}")  # type: ignore[union-attr]
        lines.append(f"raiji_rabbit_queue_memory_bytes{{{labels}}} {fmt(num(q.get('memory')))}")  # type: ignore[union-attr]

    lines += [
        "# HELP raiji_pg_up PostgreSQL respondeu",
        "# TYPE raiji_pg_up gauge",
        "# HELP raiji_pg_connections Conexões abertas",
        "# TYPE raiji_pg_connections gauge",
        "# HELP raiji_pg_size_bytes Tamanho do banco",
        "# TYPE raiji_pg_size_bytes gauge",
        "# HELP raiji_pg_cache_hit_ratio Cache hit (%)",
        "# TYPE raiji_pg_cache_hit_ratio gauge",
        "# HELP raiji_pg_deadlocks_total Deadlocks",
        "# TYPE raiji_pg_deadlocks_total counter",
        "# HELP raiji_pg_long_queries Consultas ativas > 5s",
        "# TYPE raiji_pg_long_queries gauge",
        "# HELP raiji_pg_max_query_seconds Consulta ativa mais longa (s)",
        "# TYPE raiji_pg_max_query_seconds gauge",
        "# HELP raiji_mongo_up MongoDB respondeu",
        "# TYPE raiji_mongo_up gauge",
        "# HELP raiji_mongo_connections Conexões",
        "# TYPE raiji_mongo_connections gauge",
        "# HELP raiji_mongo_ops_total Operações por tipo",
        "# TYPE raiji_mongo_ops_total counter",
        "# HELP raiji_mongo_uptime_seconds Uptime",
        "# TYPE raiji_mongo_uptime_seconds gauge",
        "# HELP raiji_mongo_resident_memory_bytes Memória residente",
        "# TYPE raiji_mongo_resident_memory_bytes gauge",
        "# HELP raiji_mongo_db_size_bytes Tamanho do banco",
        "# TYPE raiji_mongo_db_size_bytes gauge",
        "# HELP raiji_kong_up Kong respondeu",
        "# TYPE raiji_kong_up gauge",
        "# HELP raiji_kong_connections_active Conexões ativas",
        "# TYPE raiji_kong_connections_active gauge",
        "# HELP raiji_kong_connections_accepted_total Conexões aceitas",
        "# TYPE raiji_kong_connections_accepted_total counter",
        "# HELP raiji_kong_connections_handled_total Conexões tratadas",
        "# TYPE raiji_kong_connections_handled_total counter",
        "# HELP raiji_kong_workers Workers",
        "# TYPE raiji_kong_workers gauge",
    ]
    lines.append(f"raiji_pg_up {fmt(pg.get('up', 0))}")
    if pg.get("up"):
        for key in ["connections", "size_bytes", "cache_hit_ratio", "deadlocks_total",
                    "long_queries", "max_query_seconds"]:
            lines.append(f"raiji_pg_{key} {fmt(pg.get(key, 0))}")
    lines.append(f"raiji_mongo_up {fmt(mongo.get('up', 0))}")
    if mongo.get("up"):
        for key in ["connections", "uptime_seconds", "resident_memory_bytes", "db_size_bytes"]:
            lines.append(f"raiji_mongo_{key} {fmt(mongo.get(key, 0))}")
        for op in ["queries", "inserts", "updates", "deletes", "commands"]:
            lines.append(f'raiji_mongo_ops_total{{op="{op}"}} {fmt(mongo.get(op + "_total", 0))}')
    lines.append(f"raiji_kong_up {fmt(kong.get('up', 0))}")
    if kong.get("up"):
        for key in ["connections_active", "connections_accepted_total",
                    "connections_handled_total", "workers"]:
            lines.append(f"raiji_kong_{key} {fmt(kong.get(key, 0))}")

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
        "# HELP raiji_scale_timestamp_seconds Quando o resultado foi gravado",
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
        lines.append(f"raiji_scale_p50_ms{{{labels}}} {fmt(r.get('p50_ms', 0) or 0)}")
        lines.append(f"raiji_scale_p95_ms{{{labels}}} {fmt(r.get('p95_ms', 0) or 0)}")
        lines.append(f"raiji_scale_p99_ms{{{labels}}} {fmt(r.get('p99_ms', 0) or 0)}")
        lines.append(f"raiji_scale_failures{{{labels}}} {fmt(r.get('failures', 0) or 0)}")
        efficiency = (rps / instances) / base_rps if base_rps > 0 else 0.0
        lines.append(f"raiji_scale_efficiency{{{labels}}} {fmt(efficiency)}")
        lines.append(f"raiji_scale_timestamp_seconds{{{labels}}} {fmt(r.get('timestamp', 0) or 0)}")
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
    threading.Thread(target=refresh_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"probe-exporter ouvindo em :{PORT} | targets={[t[0] for t in TARGETS]}", flush=True)
    server.serve_forever()
