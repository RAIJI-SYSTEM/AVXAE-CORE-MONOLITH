# Observabilidade e testes de escalabilidade — Raiji

Stack mínima para **monitorar todos os serviços** e **comparar 1 × N instâncias**
do microsserviço, sem alterar o código das aplicações.

```
                        ┌──────────────┐
  serviços ──sondas────►│ probe-exporter│──┐
  containers ─Docker───►│  (:9101)      │  │
                        └──────────────┘  │
  host ────────────────► node-exporter ───┤──► Prometheus ──► Grafana
  RabbitMQ ────────────► :15692 ──────────┘      :9090          :3300
  resultados 1×N ──────► results/scale.json
```

## O que é coletado (sem tocar nos serviços)

| Fonte | Métrica | Como |
|---|---|---|
| Monólito | up/latência/status | sonda `http://app:8080/actuator/health` |
| Chat | up/latência/status | sonda `http://chat:3000/health` |
| Front | up/latência/status | sonda `http://host.docker.internal:3000/` |
| Kong | up/latência/status | sonda `http://kong:8000/realms/raiji` |
| Keycloak | up/latência/status | sonda `http://keycloak:8080/realms/raiji` |
| Containers `raiji-*` | CPU %, memória, rede | Docker Engine API (socket) |
| Host | CPU, RAM, disco, rede | `node_exporter` |
| RabbitMQ | filas, consumidores, vazão | plugin prometheus nativo (`:15692`) |
| Comparativo 1×N | RPS, p50/p95/p99, falhas, eficiência | `results/scale.json` (gravado pelos `run-scale.sh`) |

## Subir / derrubar

```bash
# 1) a rede do compose do monólito precisa existir
docker network inspect avxae-core-monolith_default >/dev/null 2>&1 || \
  docker network create avxae-core-monolith_default

# 2) stack de observabilidade
docker compose -p raiji-observability -f docker-compose.observability.yml up -d --build

# Grafana:    http://localhost:3300  (admin/admin)
# Prometheus: http://localhost:9090
# Exporter:   http://localhost:9101/metrics
```

Para o Grafana também enxergar o front, ele precisa estar rodando na porta
`3000` do host (o exporter usa `host.docker.internal`).

## Dashboards provisionados

| Dashboard | Uso |
|---|---|
| **Visão geral** | serviços no ar, latência das sondas, CPU/memória por container, host, filas |
| **Escalabilidade 1×N** | RPS, p95/p99, falhas, CPU/memória das réplicas, eficiência |
| **Gargalos** | latência × CPU × memória do chat, filas × consumidores, fora do ar |

## Alertas

`prometheus/alerts.yml` (visíveis em `/alerts`): serviço fora do ar, latência
> 500 ms, CPU > 85%, memória > 90%, fila acumulando, fila sem consumidores.
Sem Alertmanager, o disparo aparece no Prometheus; o Grafana pode consumir as
mesmas séries nas *alert rules*.

## Comparativo 1×N (microsserviço = chat)

O harness fica em `MESSAGE-SERVICE/load-tests/run-scale.sh`:

```bash
cd MESSAGE-SERVICE/load-tests
# carga moderada (mesma por instância, mede latência/CPU)
N_LIST="1 2 4" USERS_PER_INSTANCE=50 HOLD=20 \
RAIJI_SCALE_RESULTS=../../AVXAE-CORE-MONOLITH/observability/exporter/results/scale.json \
./run-scale.sh

# saturação com gerador distribuído e sem docker-proxy
N_LIST="1 2 4" USERS_PER_INSTANCE=100 SPAWN=400 HOLD=20 PROCS=8 USE_IPS=1 \
RAIJI_SCALE_WAIT_MIN=0 RAIJI_SCALE_WAIT_MAX=0.005 START_INFRA=1 \
RAIJI_SCALE_RESULTS=../../AVXAE-CORE-MONOLITH/observability/exporter/results/scale.json \
./run-scale.sh
```

Ele sobe N containers do chat compartilhando Mongo/Rabbit, executa o Locust com
a **mesma carga** e grava `raiji_scale_rps/p95/efficiency` para o Grafana.
`PROCS` distribui o Locust (evita limitar no gerador) e `USE_IPS=1` fala direto
no IP do container (sem `docker-proxy`).

O comparativo medido e a explicação do gargalo estão em
[`RELATORIO-ESCALABILIDADE.md`](RELATORIO-ESCALABILIDADE.md).

## Como ler o gargalo

1. **Eficiência** `E = (RPS_N / N) / RPS_1`:
   - `E ≈ 1` → escala quase linear;
   - `E < 0,8` → há recurso compartilhado limitando.
2. **CPU por réplica**:
   - ~100% e p95 subindo → gargalo de CPU da aplicação;
   - baixa e p95 alto → espera de I/O (Mongo/Rabbit/DB).
3. **Host**: CPU do host ~100% → limite vertical (todas as réplicas no mesmo host).
4. **Filas**: mensagens crescendo + consumidores constantes → consumidores não dão conta (prefetch/DB).
5. **Memória**: crescendo ao longo do tempo → leak/GC.

## Limitações

- Todas as réplicas no **mesmo host**: mede concorrência, não elasticidade real.
- Sem tracing: a correlação é por métricas e logs (`docker logs`).
- Métricas internas de Postgres/Mongo não são coletadas aqui (exigiria exporters).
- O harness usa `/health` por padrão (sem auth). Para exercitar rotas reais,
  defina `RAIJI_SCALE_TOKEN` (JWT) e `RAIJI_SCALE_PATH=/conversations`.


## Dashboards

| Dashboard | Conteúdo |
|---|---|
| **Raiji · Serviços (um por serviço)** | home: UP, latência, HTTP, CPU e memória de cada serviço |
| **Raiji · Visão geral** | latência/status, CPU/memória por container, host, RabbitMQ, rede |
| **Raiji · Escalabilidade 1×N** | RPS, p95/p99, falhas, eficiência e CPU/memória das réplicas |
| **Raiji · Gargalos** | latência × CPU × memória, filas e serviços fora do ar |
| **Raiji · Infraestrutura & Bancos** | Postgres, MongoDB, Kong, RabbitMQ, disco/inodes/load/rede e saúde dos containers |

## Métricas extras (probe-exporter v2)

- **Containers**: memória usada/limite (%), restarts, OOM kill, health, PIDs,
  throttling de CPU e I/O de disco (além de CPU e rede).
- **PostgreSQL**: UP, conexões, tamanho do banco, cache hit, deadlocks e
  consultas ativas > 5 s (via `docker exec psql`).
- **MongoDB**: UP, conexões, operações/s, memória residente, tamanho e uptime
  (via `docker exec mongosh`).
- **Kong**: UP, conexões ativas/aceitas/tratadas e workers (Admin API).
- **RabbitMQ**: filas (mensagens, prontas, não confirmadas, consumidores,
  utilização e memória) e visão geral (conexões, canais, taxas, alarmes).
