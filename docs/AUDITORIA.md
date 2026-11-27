# Auditoria — Raiji

Trilha de auditoria dos três serviços: **monólito**, **chat (MESSAGE-SERVICE)** e
**FRONT-PWA**, além dos eventos de autenticação do **Keycloak**.

O objetivo é responder, com dados reais:

- **quem** fez a ação (`actor_sub` / e-mail do Keycloak);
- **o quê** (rota, método, ação de negócio e recurso);
- **quando** (`timestamp`);
- **com qual resultado** (status HTTP e duração);
- **a partir de onde** (IP e user-agent);
- **qual requisição** (correlação por `request_id`).

> Não são gravados corpos de requisição, senhas, tokens, textos de mensagem nem
> anexos. A auditoria registra metadados de ação, não conteúdo — decisão de
> privacidade/LGPD.

## Visão por serviço

| Serviço | Log JSON (stdout) | Persistência | Consulta |
|---|---|---|---|
| Monólito | logger `AUDIT`, uma linha JSON por requisição `/api/**` | Postgres `audit_log` para requisições autenticadas que mudam estado | `GET /api/audit/me`, `GET /api/admin/audit` |
| Chat | logger Pino com `audit: true` | MongoDB `audit_events` para escrita autenticada | `GET /audit/me`, `GET /audit/events` |
| FRONT | JSON no stdout | não persiste (não tem banco); centralizado no Loki | Grafana → **Raiji · Auditoria (logs)** |
| Keycloak | evento do servidor | tabela de eventos do Keycloak | console admin / Admin API |

Todas as linhas carregam `request_id`; o FRONT gera/propaga `X-Request-Id`, o
Kong repassa e o monólito/chat registram o mesmo id. Isso permite seguir uma
ação do clique no front até o banco.

## Monólito

### Campos

| Campo | Exemplo |
|---|---|
| `event` | `http_request` |
| `request_id` | `req-...` / `kong_request_id` |
| `http_method` | `POST` |
| `http_path` | `/api/rides/{id}/requests/{requestId}` |
| `http_status` | `201` |
| `duration_ms` | `12` |
| `actor_sub` | UUID do usuário no Keycloak |
| `actor_username` | `teste` |
| `roles` | `["ROLE_DRIVER"]` |
| `client_ip` | `203.0.113.9` |
| `user_agent` | `...` |
| `action` | `BOOKING_ACCEPTED` |
| `entity_type` / `entity_id` | `RIDE_REQUEST` / UUID |
| `decision` | `ACCEPT` / `REJECT` |
| `outcome` | `SUCCESS`, `DENIED`, `FAILURE`, `ERROR` |
| `persisted` | `true` quando foi para o `audit_log` |

### Ações de negócio mapeadas

`RIDE_CREATED`, `RIDE_CANCELLED`, `RIDE_STARTED`, `RIDE_FINISHED`,
`RIDE_BOOKINGS_CLOSED`, `BOOKING_REQUESTED`, `BOOKING_ACCEPTED`,
`BOOKING_REJECTED`, `BOOKING_CANCELLED`, `BOOKINGS_ACCEPTED_ALL`,
`BAGGAGE_UPDATED`, `BAGGAGE_DECIDED`, `PAYMENT_STARTED`, `PAYMENT_WEBHOOK`,
`PAYOUT_REGISTERED`, `PIX_KEY_UPDATED`, `DELIVERY_CREATED`,
`DELIVERY_UPDATED`, `DELIVERY_CANCELLED`, `FREIGHT_CREATED`,
`FREIGHT_UPDATED` e o fallback `HTTP_<METHOD>`.

Leituras (`GET`) entram no log JSON, mas não no `audit_log` para não inflar o
banco. Escritas autenticadas e webhooks de sistema (`system`) são persistidos.

### Consultar

```bash
# últimas ações do próprio usuário (Bearer token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/api/audit/me

# visão administrativa (role admin)
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8000/api/admin/audit?actorSub=<uuid>&limit=100"

# direto no Postgres
docker exec raiji-postgres psql -U raiji -d raiji_app \
  -c "select created_at, actor_sub, action, entity_type, entity_id, details
      from audit_log order by created_at desc limit 20;"
```

## Chat

### Campos

`service`, `event`, `actor_sub`, `actor_username`, `http_method`, `http_path`,
`http_status`, `duration_ms`, `request_id`, `client_ip`, `user_agent`,
`action`, `entity_type`, `entity_id`, `persisted`, `at`.

Ações: `CONVERSATION_OPENED`, `MESSAGE_SENT`, `ATTACHMENT_CREATED`,
`CONVERSATION_READ`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `CONVERSATION_UPDATED`,
`CONVERSATION_DELETED`, `GROUP_CREATED`, `GROUP_UPDATED`, `GROUP_DELETED`,
`USER_CREATED`, `USER_DELETED`, `USER_VIEWED`, `HTTP_<METHOD>`.

O texto da mensagem **não** é registrado; apenas o id da conversa e a ação.

### Consultar

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/chat/audit/me

# admin (role chat-admin)
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8000/chat/audit/events?actorSub=<uuid>&limit=100"

# direto no Mongo
docker exec raiji-chat-mongo mongosh raiji --quiet \
  --eval 'db.audit_events.find().sort({at:-1}).limit(20).toArray()'
```

Retenção: TTL de **180 dias** no Mongo.

## FRONT-PWA

O `proxy.ts` registra a entrada de cada requisição, o `forwardToGateway` registra cada
chamada BFF com `status` e `duration_ms` e o `AuditTracker` envia um beacon
`page_view` a cada navegação client-side (Link/App Router). Quando a requisição
tem `Authorization`, o `sub` é extraído do JWT (a validação continua sendo do Kong).

```bash
docker logs raiji-front | grep '"service":"front-pwa"'
```

Navegações de página não têm ator porque o token fica no `localStorage` do
navegador; chamadas BFF e ações de negócio têm.

## Keycloak

Habilitado no realm `raiji`:

```json
"eventsEnabled": true,
"eventsExpiration": 2592000,
"adminEventsEnabled": true,
"adminEventsDetailsEnabled": true
```

Tipos: `LOGIN`, `LOGIN_ERROR`, `LOGOUT`, `REGISTER`, `REGISTER_ERROR`,
`CODE_TO_TOKEN`, `REFRESH_TOKEN`, `UPDATE_PASSWORD`, `UPDATE_PROFILE`,
`DELETE_ACCOUNT`, `RESET_PASSWORD`, entre outros.

```bash
# eventos de login do realm
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8180/admin/realms/raiji/events?type=LOGIN&max=50"

# eventos administrativos
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8180/admin/realms/raiji/admin-events?max=50"
```

O console mostra em **Realm settings → Events**. Retenção: **30 dias**.

## Centralização (Loki + Grafana)

A stack `observability/docker-compose.observability.yml` sobe **Loki** e
**Promtail**, que coleta o stdout de todos os containers `raiji-*`.

- Loki: `http://localhost:3100`
- Grafana: `http://localhost:3300` → dashboard **Raiji · Auditoria (logs)**
- Datasource provisionado: `Loki` (`uid: loki`)

Consultas úteis no Grafana Explore:

```logql
# monólito
{container="raiji-app"} | json | event="http_request"

# chat
{container="raiji-chat"} | json | audit="true"

# front
{container="raiji-front"} | json | event=~"http_request|bff_request"

# uma requisição específica em todos os serviços
{container=~"raiji-(app|chat|front)"} |= "req-123"
```

## Visão de negócio no Grafana (para não-devs)

Dashboard: `http://localhost:3300/d/raiji-auditoria-negocio`

Usuário viewer local:

```text
login: auditoria
senha: auditoria123
```

Filtros no topo:

- **Serviço**: Monólito, Chat e/ou Front. A busca principal usa todos por padrão.
- **Usuário**: nome (`maria`) ou `sub` do Keycloak. O padrão `.+` mostra só ações autenticadas; use `.*` para incluir anônimos.
- **Ação**: `RIDE_CREATED`, `MESSAGE_SENT`, `PAYMENT_WEBHOOK` etc.
- **Status**: 2xx, 4xx, 5xx ou falhas.
- **Busca livre**: `request_id`, rota ou texto.

Painéis:

- totais de ações, falhas, ação e usuário mais ativos;
- **todas as ações dos três serviços em um único painel**, com linha padronizada
  (`usuário | ação | método rota | status | duração | request_id`);
- erros e negações;
- tabelas de ações por serviço, top ações e top usuários;
- **Usuários — estatísticas** (total de ações por usuário);
- **Falhas por usuário**;
- **Usuários × ações** (o que cada usuário fez);
- identidade do Keycloak (login/logout e admin events);
- logs técnicos de Kong/Keycloak/Postgres/RabbitMQ;
- busca livre em todos os containers `raiji-*`.

As consultas normalizam `actor_sub`/`actorSub`, `http_status`/`status` e
`action`/`event` para permitir filtrar os três serviços juntos.

Todos os painéis de ações e de resumo (totais, top usuários, usuários —
estatísticas, falhas por usuário, usuários × ações e ações por serviço)
respeitam os cinco filtros. Se o filtro **Usuário** não corresponder a ninguém,
todos eles ficam vazios (`No data`) — inclusive Top usuários. O painel de
identidade do Keycloak respeita **Usuário** e **Busca livre**. Já os painéis de
infraestrutura (Kong/Keycloak/Postgres/RabbitMQ) e a busca livre em todos os
containers respeitam apenas a **Busca livre**.

O painel de identidade é alimentado pelo container `raiji-keycloak-events`, que
consulta a Admin API do Keycloak a cada 10s e publica os eventos em JSON no
stdout. O Promtail coleta e envia ao Loki.

O dashboard técnico anterior continua disponível em
`http://localhost:3300/d/raiji-auditoria`.

## Dashboard técnico — todos os logs

Dashboard: `http://localhost:3300/d/raiji-auditoria`

Ele reúne:

- **Busca global** em todos os containers, com filtros de serviço, usuário, ação,
  status e texto/`request_id`;
- logs HTTP de monólito, chat, front e Keycloak (Loki);
- **logs de execução dos testes** de `reports/testes/logs/` dos três repositórios
  (Promtail lê os arquivos via mount `/workspace`);
- **`audit_log` do Postgres** em uma tabela (datasource `Postgres Auditoria`);
- eventos de identidade do Keycloak (exportador);
- logs de infraestrutura (Kong, Postgres, RabbitMQ);
- painel de links e comandos úteis.

Filtros do dashboard:

| Filtro | Uso |
|---|---|
| **Serviço** | Monólito, Chat, Front, Keycloak ou Infra na busca global |
| **Usuário** | nome ou `sub` do Keycloak |
| **Ação** | `RIDE_CREATED`, `MESSAGE_SENT`, `PAYMENT_WEBHOOK` etc. |
| **Status** | 2xx, 4xx, 5xx ou falhas |
| **Busca** | texto, rota ou `request_id` em todos os logs |
| **Serviço (testes)** | filtra os logs de execução dos testes |
| **Busca SQL** | filtra `audit_log`; padrão `%`, use `%termo%` |

O padrão de **Usuário** é `.+` (somente autenticados). Use `.*` para incluir
navegações anônimas do front.

Painéis de usuários no dashboard técnico:

- **Usuários ativos (24h)** — quantidade de usuários distintos no monólito/chat e no front.
- **Usuários — o que estão fazendo (monólito/chat)** — tabela Usuário × Ação × Ações.
- **Usuários — o que estão fazendo (front)** — tabela Usuário × Evento × Ações.
- **Últimas ações por usuário** — linha do tempo das ações com usuário, ação, rota, status, duração e `request_id`.

O mount do workspace no Promtail usa `WORKSPACE_DIR` (padrão `../..`, relativo
ao diretório `observability`). Para apontar para outro local:

```bash
WORKSPACE_DIR=/caminho/do/workspace   docker compose -p raiji-observability -f docker-compose.observability.yml up -d promtail
```

O datasource Postgres é local (`postgres:5432`, banco `raiji_app`). Em produção,
restrinja o acesso a esse datasource e use um usuário somente leitura.

## Retenção e privacidade

| Camada | Retenção |
|---|---|
| `audit_log` (Postgres) | sem expiração automática; limpar por política |
| `audit_events` (Mongo) | 180 dias (TTL) |
| Eventos Keycloak | 30 dias (`eventsExpiration`) |
| Loki | 30 dias (`retention_period: 720h`) |

A trilha não guarda conteúdo de mensagem, corpo de requisição, token ou senha.
Para um requisito de “gravar tudo”, prefira metadados de ação e correlação a
capturar payloads sensíveis.
