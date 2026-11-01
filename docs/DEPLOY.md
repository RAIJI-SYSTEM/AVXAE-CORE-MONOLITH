# DEPLOY — S1-03 · CI/CD + Homologação na AWS

Este documento descreve o pipeline de CI/CD do **AVXAE-CORE-MONOLITH (Raiji)**,
os ambientes, a configuração da AWS, as **proteções de release** (rollback
automático) e o procedimento de rollback manual.

> **Escolha do provedor.** A issue sugeria Render/Railway/Fly.io, mas a decisão
> do time foi **AWS** (ECR + EC2 + docker compose), reaproveitando o
> `docker-compose.yml` existente e mantendo custo baixo. As limitações estão na
> seção [Limitações](#10-limitações-do-ambiente-experimental).

---

## 1) Ambientes e branches

| Ambiente  | Branch gatilho | Workflow                 | Porta pública (Kong) | URL                                          |
|-----------|----------------|--------------------------|----------------------|----------------------------------------------|
| `dev`     | `develop`      | `deploy-dev.yml`         | 8000*                | `http://<AWS_EC2_HOST_DEV>:8000`             |
| `homolog` (sistema) | `main` | `deploy-homolog.yml`     | 9000*                | `http://<AWS_EC2_HOST_HOMOLOG>:9000`         |

\* portas definidas em `infra/aws/aws-config.env` (veja seção 3).

* Push em `develop` → atualiza o **ambiente dos devs** automaticamente.
* Push em `main` → atualiza o **ambiente do sistema (homologação)** automaticamente.
* Ambos chamam o workflow reutilizável `.github/workflows/deploy.yml`.
* **CI** (`ci.yml`) roda em todo PR e em push na `main`/`develop`.
* **Guardian** (`guardian.yml`) vigia os dois ambientes a cada 10 min (seção 8).

### URL pública dos endpoints (via Kong)

| Endpoint | URL |
|---|---|
| Health da API | `http://<HOST>:<PORTA>/actuator/health` |
| Realm Keycloak | `http://<HOST>:<PORTA>/realms/raiji` |
| Token OIDC | `POST http://<HOST>:<PORTA>/realms/raiji/protocol/openid-connect/token` |
| API | `http://<HOST>:<PORTA>/api/...` (ex.: `/api/me`) |

---

## 2) Arquitetura do deploy (AWS)

```
GitHub Actions                          AWS
──────────────                          ───────────────────────────────
push develop/main
  │
  ├─ config (lê infra/aws/aws-config.env: região + portas)
  ├─ tests (unitários, mvn -T 1C)
  ├─ build-push ── docker build ──────▶ ECR  raiji-app
  │     retag <env>-current →            ├─ <env>-<sha>      (histórico)
  │           <env>-previous             ├─ <env>-current    (em uso)
  │                                      └─ <env>-previous   (rollback)
  ├─ deploy ── ssh ───────────────────▶ EC2 (tipo definido no config)
  │     deploy-remote.sh                 docker compose -p raiji-<env>
  │     health-gate + self-rollback        ├─ postgres (interno)
  │     (sem anterior: app PARADO)         ├─ keycloak (KC_PORT)
  ├─ smoke (4 jobs PARALELOS)              ├─ app  (imagem ECR, interno)
  │     health · realm · token · me        └─ kong (BASE_PORT = entrada única)
  ├─ release ✅ (URL no sumário)
  └─ rollback (automático se falhar)

Guardian (cron */10, ambos ambientes em paralelo):
  smoke → falhou? reconfirma → confirmou? rollback automático → Issue de alerta
```

Os dois ambientes podem morar **na mesma instância** (projetos compose
separados `raiji-dev` / `raiji-homolog`, portas 8000/9000) ou em instâncias
distintas — o deploy não muda.

---

## 3) Configuração AWS fácil — `infra/aws/aws-config.env`

**Fonte única** lida pelo `bootstrap-aws.sh` e por todos os workflows
(deploy, rollback, guardian). Para mudar, edite o arquivo e commit:

| Chave | Default dev | Default homolog | O que muda |
|---|---|---|---|
| `AWS_REGION` | `us-east-1` (global) | idem | região de ECR/EC2 |
| `INSTANCE_TYPE_DEV` / `INSTANCE_TYPE_HOMOLOG` | `t3.small` | `t3.small` | **tipo de máquina** do deploy |
| `BASE_PORT_DEV` / `BASE_PORT_HOMOLOG` | `8000` | `9000` | porta pública (Kong) |
| `KC_PORT_DEV` / `KC_PORT_HOMOLOG` | `8180` | `9180` | porta do Keycloak |
| `AMI_DEV` / `AMI_HOMOLOG` | vazio (Ubuntu 24.04) | idem | AMI customizada |

### Mudar o tipo de máquina (exemplo)

```bash
# 1) edite infra/aws/aws-config.env
sed -i 's/^INSTANCE_TYPE_HOMOLOG=.*/INSTANCE_TYPE_HOMOLOG=t3.medium/' infra/aws/aws-config.env
git commit -am "infra: homolog agora roda em t3.medium"

# 2) rerode o bootstrap — ele PARA a instância, aplica o novo tipo e RELIGA
./scripts/aws/bootstrap-aws.sh --env homolog
```

Mudanças de porta/região valem automaticamente no próximo deploy (os
workflows leem o arquivo no job `config`); para portas, rerode o bootstrap
uma vez para ajustar o security group. Flags de CLI do bootstrap
(`--instance-type`, `--base-port`, `--region`, `--ami`, `--config`) têm
precedência sobre o arquivo (útil para teste pontual).

---

## 4) Configuração no GitHub (uma vez)

### 4.1 Bootstrap da infra AWS

```bash
# na sua máquina, com aws cli v2 configurado (permissões EC2/IAM/ECR)
./scripts/aws/bootstrap-aws.sh --env dev
./scripts/aws/bootstrap-aws.sh --env homolog
```

O script cria ECR (`raiji-app`), security group, key pair, IAM role (pull ECR)
e a instância EC2 com Docker instalado, e **imprime no final exatamente os
valores** para configurar no GitHub.

### 4.2 Secrets e Variables do repositório

**Variables** (Settings → Secrets and variables → Actions → *Variables*):

| Variável | Valor |
|---|---|
| `AWS_EC2_HOST_DEV` | IP público da instância dev |
| `AWS_EC2_HOST_HOMOLOG` | IP público da instância homolog (pode ser a mesma) |

**Secrets do repositório** (aba *Secrets*):

| Segredo | Valor |
|---|---|
| `AWS_ACCESS_KEY_ID` | chave do usuário IAM usado pelo Actions (ECR push/retag) |
| `AWS_SECRET_ACCESS_KEY` | idem |

**Secrets por Environment** (Settings → Environments → `dev` / `homolog`):

| Segredo | Environment `dev` | Environment `homolog` | Valor |
|---|---|---|---|
| Chave SSH da EC2 | `AWS_SSH_KEY_DEV` | `AWS_SSH_KEY_HOMOLOG` | conteúdo da `.pem` gerada no bootstrap |
| Senha do banco | `DB_PASSWORD_DEV` | `DB_PASSWORD_HOMOLOG` | senha forte do Postgres/Keycloak |
| Segredo do client admin | `KEYCLOAK_ADMIN_CLIENT_SECRET_DEV` | `KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG` | segredo do client `raiji-admin` |
| Usuário do smoke (opcional) | `SMOKE_USERNAME_DEV` | `SMOKE_USERNAME_HOMOLOG` | padrão: `teste` |
| Senha do smoke (opcional) | `SMOKE_PASSWORD_DEV` | `SMOKE_PASSWORD_HOMOLOG` | padrão do realm: `teste123` — **troque em homolog** |

> Dica: no Environment `homolog`, ative **Required reviewers** para exigir
> aprovação manual antes do deploy do sistema.

### 4.3 Permissões do usuário IAM do Actions (mínimas)

```json
{
  "Statement": [
    { "Effect": "Allow", "Action": ["ecr:GetAuthorizationToken"], "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:GetDownloadUrlForLayer",
                 "ecr:BatchGetImage","ecr:PutImage","ecr:InitiateLayerUpload",
                 "ecr:UploadLayerPart","ecr:CompleteLayerUpload","ecr:DescribeImages"],
      "Resource": "arn:aws:ecr:*:*:repository/raiji-app" },
    { "Effect": "Allow", "Action": ["sts:GetCallerIdentity"], "Resource": "*" }
  ]
}
```

---

## 5) Variáveis de ambiente do provedor (instância)

Geradas automaticamente pelo deploy em `/opt/raiji/<env>/.env` (umask 077,
**nunca commitadas**). São exatamente as exigidas pela issue:

| Variável | Valor padrão | Uso |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` | perfil Spring da aplicação |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | (segredo GitHub) | service account `raiji-admin` |
| `DB_URL` | `jdbc:postgresql://postgres:5432/raiji_app` | datasource do app |
| `DB_USER` | `raiji` | usuário do Postgres |
| `DB_PASSWORD` | (segredo GitHub) | senha do Postgres |
| `JWK_SET_URI` | `http://keycloak:8080/realms/raiji/protocol/openid-connect/certs` | validação de JWT |
| `IMAGE_TAG` | `<env>-current` | imagem do ECR em execução |
| `BASE_PORT` / `KC_PORT` | vindos do `aws-config.env` | portas públicas |

O override `docker-compose.aws.yml` traduz `DB_URL/DB_USER/DB_PASSWORD/JWK_SET_URI`
para as propriedades Spring (`SPRING_DATASOURCE_*`,
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`) sem mudar código.

---

## 6) CI (`.github/workflows/ci.yml`)

Trilhas **paralelas** em todo PR / push (`main`, `develop`):

1. **unit-tests** — matriz *dinâmica*: os módulos são lidos do `pom.xml` em
   runtime; **módulos novos entram na matriz automaticamente** e rodam em
   paralelo (um job por módulo, `fail-fast: false`).
2. **verify** — pipeline da issue: checkout → setup JDK 21 → cache Maven →
   `mvn clean verify` (unitários + integração Testcontainers + JaCoCo ≥ 80%) →
   upload do relatório JaCoCo e dos relatórios de teste.
3. **docker-image** — build da imagem (BuildKit + cache GHA) para garantir que
   o CD continua publicável.

Otimizações: `concurrency` cancela execuções obsoletas do mesmo ref;
`mvn -T 1C` paraleliza o build por núcleos; cache Maven via `setup-java`.

> Ative **branch protection** em `main`/`develop` exigindo o status `CI` para
> merge: alteração quebrada nem chega a disparar o CD.

---

## 7) Smoke tests (`scripts/smoke-test.sh`)

Validam exatamente o que a issue pede:

| Check | Requisição |
|---|---|
| `health` | `GET /actuator/health` → 200 e `{"status":"UP"}` |
| `realm` | `GET /realms/raiji` no Keycloak → 200 |
| `token` | `POST /realms/raiji/protocol/openid-connect/token` (usuário de teste) → 200 |
| `me` | `GET /api/me` com Bearer token → 200 |

No CD, os 4 checks rodam como **matriz paralela** (4 jobs simultâneos, um por
check — o check `me` obtém o próprio token, então não há dependência).
**Falha em qualquer check bloqueia o release** e dispara rollback automático.

Execução manual:

```bash
BASE_URL=http://<HOST>:8000 ./scripts/smoke-test.sh                # paralelo (padrão)
BASE_URL=http://<HOST>:9000 ./scripts/smoke-test.sh --serial
./scripts/smoke-test.sh --check health --retries 18 --retry-delay 10
```

---

## 8) Proteções de release — versão quebrada nunca fica no ar

Camadas de proteção (valem **igualmente para dev e homolog**):

| # | Camada | Onde | O que impede |
|---|---|---|---|
| 1 | Branch protection + CI verde | `ci.yml` no PR | código quebrado de chegar na `main`/`develop` |
| 2 | Testes unitários antes do build | job `tests` do `deploy.yml` | build de imagem a partir de código que não compila/passa |
| 3 | **Health-gate na instância** | `deploy-remote.sh` | novo container assumir o tráfego doente |
| 4 | **Self-rollback local** | `deploy-remote.sh` | versão nova quebrada continuar rodando |
| 5 | **Smoke paralelo bloqueia release** | job `smoke` → `release` | release declarado com API/Keycloak/auth quebrados |
| 6 | **Rollback automático do workflow** | job `rollback` do `deploy.yml` | permanecer na versão ruim após falha de smoke |
| 7 | **Guardian (erros inesperados pós-deploy)** | `guardian.yml` + `guardian.sh` | erro que só aparece DEPOIS (crash, OOM, dependência) — detecta em ≤10 min, reverte e alerta |

### Comportamento quando NÃO existe versão anterior (1º deploy)

O requisito "*não deixe subir até que tudo suba corretamente*" é garantido assim:

* O health-gate falhou e **não há** versão anterior → o `deploy-remote.sh`
  **para o container do app**: nada quebrado é servido; o workflow falha e o
  release não é declarado. O ambiente só volta a responder quando um deploy
  verde for publicado.
* O `rollback.sh` (automático ou manual), sem tag `<env>-previous` no ECR,
  também para o app e falha com mensagem clara — não há para onde voltar.
* O Guardian, sem histórico, não tenta rollback: apenas **abre uma Issue**
  de alerta.

### Guardian — `guardian.yml` (a cada 10 min + manual)

Para cada ambiente (jobs paralelos, serializados com o deploy pelo mesmo
concurrency group):

1. Smoke completo (4 checagens em paralelo, com retries).
2. Falhou → aguarda `GUARDIAN_RECHECK_DELAY` (60 s) e **reconfirma** (evita
   falso positivo de rede).
3. Falha confirmada + existe `<env>-previous` → **rollback automático**
   (retag + redeploy + smoke).
4. Irrecuperável → **Issue `[Guardian] Ambiente <env> indisponível`**
   (sem duplicar: só cria se não houver Issue aberta com o mesmo título).

---

## 9) Rollback

### Automático (workflow de deploy e Guardian)

1. **Retag da imagem anterior** no ECR: `<env>-previous` → `<env>-current`
   (`scripts/aws/ecr-retag.sh`, sem re-upload de camadas).
2. **Redeploy**: SSH na EC2 → `compose pull app` + `up -d --no-deps app` + health-gate.
3. **Smoke completo** (paralelo) para confirmar a recuperação.

A tag `<env>-previous` é preservada *antes* de cada deploy (job `build-push`),
e a instância guarda a versão anterior em `.deploy-state` (self-rollback local).

### Manual (testado ao menos uma vez — critério de aceite)

* **Via GitHub:** Actions → **Rollback (manual)** → *Run workflow* → ambiente.
* **Via linha de comando** (máquina com aws cli + ssh):

```bash
export ENVIRONMENT=homolog EC2_HOST=<IP> EC2_USER=ubuntu BASE_PORT=9000
export SSH_PRIVATE_KEY="$(cat ~/.ssh/raiji-homolog-pem)"
./scripts/aws/rollback.sh
```

### Manual "na mão" (emergência, direto na instância)

```bash
ssh -i raiji-<env>-pem ubuntu@<IP>
cd /opt/raiji/<env>
cat .deploy-state.previous                 # tag anterior
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<tag-anterior>/' .env
docker compose -p raiji-<env> -f docker-compose.yml -f docker-compose.aws.yml up -d --no-deps app
```

---

## 10) Testes da própria esteira (`tests/cicd/`)

```bash
tests/cicd/run-all.sh            # suíte completa
tests/cicd/run-all.sh --quick    # sem o teste funcional (mock server)
```

A suíte verifica: sintaxe dos scripts (`bash -n`), validade dos workflows
(`actionlint`), estrutura/semântica dos YAMLs (gatilhos por branch, matriz
paralela, gates, rollback, guardian, config central), conteúdo do `DEPLOY.md`,
ausência de segredos commitados, idempotência do `setup-cicd.sh` e o
**comportamento real** do `smoke-test.sh` contra um servidor mock (cenários
de sucesso e de falha).

---

## 11) Limitações do ambiente experimental

* **Custo/Free tier:** a conta AWS nova tem 750 h/mês de `t2.micro` por 12
  meses, porém o stack (Keycloak 26 + Spring Boot + Kong + Postgres) precisa de
  **~2 GB de RAM** → usamos `t3.small` (~US$ 15/mês por ambiente, fora do free
  tier). Mitigação: rodar os dois ambientes na **mesma instância** `t3.medium`
  (projetos compose separados, portas 8000/9000) — basta apontar as duas
  `AWS_EC2_HOST_*` para o mesmo IP.
* **Sem HTTPS/domínio:** o acesso é por `http://IP:porta`. Para HTTPS, aponte
  um domínio + ALB/CloudFront com ACM (fora do escopo da S1-03).
* **Instância única por ambiente:** sem HA; IP público pode mudar em stop/start
  (atualize a variable `AWS_EC2_HOST_<ENV>`; use Elastic IP para fixar). O
  resize (mudança de tipo) também exige parar/religar a instância.
* **Banco na própria instância** (container + volume): aceitável para
  homologação; em produção usar RDS.
* **SSH aberto (porta 22) para 0.0.0.0/0** no bootstrap — restrinja o CIDR do
  security group para os IPs do GitHub Actions ou use SSM Session Manager.
* **Guardian:** schedules do GitHub podem atrasar alguns minutos em horários
  de pico; o intervalo efetivo de detecção é ~10-15 min.
* **Smoke `me`** depende do usuário `teste` do realm; troque a senha padrão em
  homolog e reflita em `SMOKE_PASSWORD_HOMOLOG`.

---

## 12) Checklist de implantação inicial

- [ ] `bootstrap-aws.sh --env dev` e `--env homolog` executados
- [ ] Variables `AWS_EC2_HOST_DEV` / `AWS_EC2_HOST_HOMOLOG` criadas
- [ ] Secrets de repositório (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) criados
- [ ] Environments `dev` e `homolog` com seus secrets (`AWS_SSH_KEY_*`, `DB_PASSWORD_*`, `KEYCLOAK_ADMIN_CLIENT_SECRET_*`, `SMOKE_*`)
- [ ] (Opcional) Required reviewers no environment `homolog`
- [ ] (Recomendado) Branch protection em `main`/`develop` exigindo o status `CI`
- [ ] Push em `develop` → workflow **Deploy DEV** verde → smoke OK
- [ ] Push em `main` → workflow **Deploy HOMOLOG** verde → URL acessível
- [ ] Rollback manual testado uma vez (Actions → *Rollback (manual)*)
- [ ] Guardian testado uma vez (Actions → *Guardian* → `all`)
