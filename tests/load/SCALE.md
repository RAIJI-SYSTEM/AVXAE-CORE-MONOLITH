# Escalabilidade (1×N) — AVXAE-CORE-MONOLITH

O monólito não é iniciado por este script (precisa de Postgres/Keycloak).
Informe as instâncias já em execução:

```bash
RAIJI_SCALE_URLS="http://127.0.0.1:8000,http://127.0.0.1:8001" \
N_LIST="1 2" USERS_PER_INSTANCE=100 PROCS=4 HOLD=60 \
RAIJI_SCALE_RESULTS=../observability/exporter/results/scale.json \
./run-scale.sh
```

| Variável | Efeito |
|---|---|
| `RAIJI_SCALE_URLS` | instâncias (obrigatório) |
| `N_LIST` | quantas URLs usar por rodada |
| `USERS_PER_INSTANCE` | carga por instância |
| `PROCS` | processos Locust |
| `RAIJI_SCALE_TOKEN/PATH` | rota autenticada (ex.: `/api/me`) |

Por padrão exercita `/actuator/health` (sem autenticação). Para capacidade real,
use um token JWT e `RAIJI_SCALE_PATH=/api/me` (ou outra rota de negócio).
