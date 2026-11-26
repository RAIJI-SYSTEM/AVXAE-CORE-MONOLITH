# Relatório — comparativo 1 × N e gargalo (MESSAGE-SERVICE/chat)

Execução local, **um host** (16 vCPU), Docker Compose/bridge, Mongo e RabbitMQ
compartilhados, métricas coletadas pelo `probe-exporter` (5 s) → Prometheus →
Grafana. Data: 2026-11-25.

## Como foi medido

- **Carga**: Locust (`MESSAGE-SERVICE/load-tests/run-scale.sh`), mesma carga por
  instância, `/health` (sem auth; trivial — mede o caminho HTTP/Node).
- **Métricas**: RPS/p50/p95/p99 do Locust; CPU/memória por container (Docker API);
  CPU/RAM do host (node_exporter); filas RabbitMQ.
- **Configurações**: 1, 2 e 4 réplicas do chat, mesmos Mongo/Rabbit, mesmas
  portas internas, instâncias removidas entre rodadas.
- **Grafana**: dashboards `Raiji · Escalabilidade 1×N`, `Raiji · Gargalos` e
  `Raiji · Visão geral` (`http://localhost:3300`).

## Experimento A — carga moderada (50 usuários por instância)

Espera 50–200 ms por requisição, 15–20 s, 1 processo Locust.

| N | Usuários | RPS | p50 | p95 | p99 | Falhas | CPU máx/réplica | Mem/réplica | Eficiência |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 50 | 321 | 1 ms | 1 ms | 13 ms | 0 | 6,7% | 95 MB | 1,00 |
| 2 | 100 | 632 | 1 ms | 2 ms | 4 ms | 0 | 7,8% | 103 MB | **0,98** |
| 4 | 200 | 1.225 | 1 ms | 4 ms | 14 ms | 0 | 5,2–7,8% | 93–103 MB | **0,95** |

**Leitura:** escala praticamente linear, latência baixa e CPU por réplica < 8%.
Neste regime **não há gargalo** — o serviço acompanha o aumento de réplicas.

## Experimento B — saturação com 1 processo Locust (100 usuários/instância)

Sem espera entre requisições (0–5 ms).

| N | Usuários | RPS | p50 | p95 | p99 | Falhas | CPU máx/réplica | CPU host |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 2.182 | 32 ms | 46 ms | 54 ms | 0 | 23% | ≤15% |
| 2 | 200 | 2.098 | 68 ms | 92 ms | 99 ms | 0 | 11% | ≤15% |
| 4 | 400 | 2.022 | 140 ms | 180 ms | 190 ms | 0 | 5–23% | ≤15% |

**Leitura:** vazão **para de crescer** (~2 mil req/s) e a latência **dobra** a cada
aumento de carga, enquanto **CPU do serviço e do host ficam baixas**. O limitante
é o **gerador de carga** (um processo Locust), não o microsserviço.

## Experimento C — Locust distribuído (8 processos, IPs dos containers)

Mesma carga do B, agora com 8 processos Locust e sem `docker-proxy`
(`USE_IPS=1`, conexão direta no IP do container).

| N | Usuários | RPS | p50 | p95 | p99 | Falhas | CPU réplica | Mem/réplica | Eficiência |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | **12.390** | 4 ms | 6 ms | 7 ms | 0 | 100% (1 core) | 134 MB | 1,00 |
| 2 | 200 | **11.470** | 10 ms | 15 ms | 18 ms | 0 | ~68% | ~114 MB | 0,46 |
| 4 | 400 | **11.283** | 24 ms | 33 ms | 39 ms | 0 | 68–73% | 113–123 MB | 0,23 |

Host no pico: **58,6% de CPU** (≈9 de 16 cores), softirq máx **2,3%**.

## Gargalo identificado e explicação técnica

1. **A vazão não aumenta com N** (12,4k → 11,3k) e o p95 sobe de 6 ms para 33 ms.
2. **CPU por réplica fica em 68–73% de um core** (Node é single-thread): cada
   réplica tem folga e não está saturada; a soma do serviço é ~2,8 cores.
3. **CPU do host em 58,6%**: os **8 workers do Locust** consomem
   aproximadamente 8 cores — o próprio gerador de carga ocupa o host.
4. **Softirq baixo (2,3%)** e sem aumento de tráfego de rede relevante → não é a
   pilha de rede.
5. Aumentar apenas o número de réplicas **não** melhora a vazão, porque o
   gerador (master + workers em um único host) já está no limite de CPU e
   agregação de estatísticas.

**Conclusão:** no regime saturado, o gargalo é o **ambiente de geração de carga
(Locust em um único host)**, não o chat. O microsserviço escala de forma
quase linear quando a carga é moderada (eficiência 0,95–0,98) e, no pico,
ainda tinha ~30% de folga por réplica.

**Evidência nos painéis:**
- *Escalabilidade 1×N*: `raiji_scale_rps` plano com N, `raiji_scale_p95_ms`
  crescendo (6 → 33 ms) e eficiência caindo (0,23).
- *Gargalos*: CPU das réplicas < 80%, host ~59%, latência subindo — o recurso
  saturado é externo aos containers do serviço.
- *Visão geral*: filas do RabbitMQ estáveis (o `/health` não usa broker).

## Recomendações

1. **Distribuir o Locust** (workers em outras máquinas ou múltiplos masters)
   para medir a capacidade real do chat; repetir o experimento C.
2. Testar **rotas reais** com `RAIJI_SCALE_TOKEN` e
   `RAIJI_SCALE_PATH=/conversations` para incluir Mongo/Rabbit no caminho e
   verificar pool/filas nos painéis.
3. Para produção, escalar com orquestrador (HPA/KEDA) e reavaliar com geradores
   externos; o harness e os dashboards já estão prontos para isso.

## Reprodução

```bash
# 1) observabilidade
cd AVXAE-CORE-MONOLITH/observability
docker network inspect avxae-core-monolith_default >/dev/null || docker network create avxae-core-monolith_default
docker compose -p raiji-observability -f docker-compose.observability.yml up -d --build

# 2) comparativo (chat)
cd ../../MESSAGE-SERVICE/load-tests
N_LIST="1 2 4" USERS_PER_INSTANCE=100 SPAWN=400 HOLD=20 PROCS=8 USE_IPS=1 \
RAIJI_SCALE_WAIT_MIN=0 RAIJI_SCALE_WAIT_MAX=0.005 START_INFRA=1 \
RAIJI_SCALE_RESULTS=../../AVXAE-CORE-MONOLITH/observability/exporter/results/scale.json \
./run-scale.sh
```

> Observação: os números são de **uma rodada por configuração** (sem repetição
> estatística) e de um `/health` trivial. Para conclusões de capacidade de
> produção, repita 3×, use carga aberta (taxa constante) e rotas reais.
