"""Configuração única dos testes de carga do ecossistema Raiji.

Tudo vem de variáveis de ambiente, com defaults que funcionam no docker-compose
local (Kong em :8000, Next em :3000). Ver `.env.example`.
"""

from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _bool(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).strip().lower() in ("1", "true", "yes", "y", "on")


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


# --------------------------------------------------------------------------- #
# Endereços
# --------------------------------------------------------------------------- #
# Kong é o único ponto de entrada do backend (monolito + chat + mídia + Keycloak).
GATEWAY = os.getenv("RAIJI_GATEWAY", "http://localhost:8000").rstrip("/")
# Next.js (BFF + SSR). Os testes do front passam por aqui.
FRONT = os.getenv("RAIJI_FRONT", "http://localhost:3000").rstrip("/")
# Keycloak direto (usado só pelo script de seed de usuários; no teste quem
# atende é o Kong). Console local: 127.0.0.1:8180.
KEYCLOAK_ADMIN = os.getenv("RAIJI_KEYCLOAK_ADMIN", "http://localhost:8180").rstrip("/")

# Prefixos roteados pelo Kong (kong/kong.yml.tmpl)
API_PREFIX = os.getenv("RAIJI_API_PREFIX", "/api")
CHAT_PREFIX = os.getenv("RAIJI_CHAT_PREFIX", "/chat")
MEDIA_PREFIX = os.getenv("RAIJI_MEDIA_PREFIX", "/raiji-media")

# --------------------------------------------------------------------------- #
# Autenticação (realm raiji / client público raiji-api, password grant)
# --------------------------------------------------------------------------- #
REALM = os.getenv("RAIJI_REALM", "raiji")
CLIENT_ID = os.getenv("RAIJI_CLIENT_ID", "raiji-api")
CLIENT_SECRET = os.getenv("RAIJI_CLIENT_SECRET", "")  # vazio: client público
TOKEN_PATH = f"/realms/{REALM}/protocol/openid-connect/token"

# Arquivo CSV gerado por scripts/seed_test_users.py
USERS_FILE = Path(os.getenv("RAIJI_LOAD_USERS_FILE", str(ROOT / "users.csv")))
# Credencial de emergência (usuário padrão do realm). Só é usada se não houver CSV.
FALLBACK_USER = os.getenv("RAIJI_USER", "teste")
FALLBACK_PASSWORD = os.getenv("RAIJI_PASSWORD", "teste123")

# Renova o token quando faltar menos que isto para expirar (segundos).
TOKEN_REFRESH_MARGIN = _int("RAIJI_TOKEN_REFRESH_MARGIN", 45)

# --------------------------------------------------------------------------- #
# Comportamento dos testes
# --------------------------------------------------------------------------- #
# Timeout (s) de cada requisição. O Kong corta em 60s; o Locust deve desistir antes.
HTTP_TIMEOUT = _float("RAIJI_HTTP_TIMEOUT", 20.0)
# Nome do host que aparece no relatório (Locust usa host= do CLI, mas deixamos aqui).
NAME_PREFIX = os.getenv("RAIJI_NAME_PREFIX", "")

# Quais serviços fazem parte do escopo desta suíte. Nas cópias por repositório,
# o .env desliga o que não pertence àquele serviço (ex.: no monolito, chat=false).
MONOLITH_ENABLED = _bool("RAIJI_MONOLITH_ENABLED", True)
CHAT_ENABLED = _bool("RAIJI_CHAT_ENABLED", True)
FRONT_ENABLED = _bool("RAIJI_FRONT_ENABLED", True)

# Fluxos que criam dados: ligados por padrão (é um ambiente de teste), mas podem
# ser desligados para rodar carga somente-leitura contra um ambiente compartilhado.
ENABLE_WRITES = _bool("RAIJI_ENABLE_WRITES", True)
# Fluxos que dependem de serviços externos pagos (Stripe, Infosimples, Resend).
# Desligados por padrão: não devem ser martelados em teste de carga.
ENABLE_EXTERNAL = _bool("RAIJI_ENABLE_EXTERNAL", False)
# Upload real de mídia no S3/RustFS (custo de banda/disco).
ENABLE_MEDIA_UPLOAD = _bool("RAIJI_ENABLE_MEDIA_UPLOAD", False)

# Limites de sanidade para não explodir o banco em runs longos.
MAX_RIDES_PER_USER = _int("RAIJI_MAX_RIDES_PER_USER", 20)
SEARCH_PAGE_SIZE = _int("RAIJI_SEARCH_PAGE_SIZE", 20)

# Cidades usadas nos payloads (precisam existir no seed de dados do monolito,
# se o ambiente exigir cidade cadastrada).
CITIES = [
    ("São Paulo", "SP"),
    ("Campinas", "SP"),
    ("Rio de Janeiro", "RJ"),
    ("Belo Horizonte", "MG"),
    ("Curitiba", "PR"),
]


# --------------------------------------------------------------------------- #
# Mensageria (AMQP) — usado por messaging_locustfile.py
# --------------------------------------------------------------------------- #
AMQP_URL = os.getenv("RAIJI_AMQP_URL", "amqp://raiji:raiji123@localhost:5672/%2f")
AMQP_TIMEOUT = _float("RAIJI_AMQP_TIMEOUT", 5.0)
AMQP_PROBE_ROUTING_KEY = os.getenv("RAIJI_AMQP_PROBE_ROUTING_KEY", "loadtest.probe")
AMQP_PROBE_TIMEOUT = _float("RAIJI_AMQP_PROBE_TIMEOUT", 1.0)
MESSAGING_ENABLED = _bool("RAIJI_MESSAGING_ENABLED", True)

RIDES_EXCHANGE = os.getenv("RAIJI_RIDES_EXCHANGE", "raiji.rides")
USERS_EXCHANGE = os.getenv("RAIJI_USERS_EXCHANGE", "raiji.users")
CHAT_EXCHANGE = os.getenv("RAIJI_CHAT_EXCHANGE", "raiji.chat")
