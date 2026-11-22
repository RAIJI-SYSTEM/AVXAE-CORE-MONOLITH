# CD — checklist para o deploy funcionar

Passo a passo do que **só você pode fazer** (conta AWS e configuração do
GitHub). O código já está preparado; o pipeline falha hoje por falta de
credenciais/configuração, não por bug de aplicação.

> Diagnóstico rápido: `./scripts/aws/check-github-config.sh`
> (requer `gh auth login`). Ele lista OK/FALTA de secrets, variables,
> environments e workflows.

> **Atalho TUDO EM UM:** `./cd-setup-all.sh` (NA RAIZ do projeto) faz tudo:
> implementas as correções nos arquivos (fase APPLY), conecta na AWS, roda o
> bootstrap dos dois ambientes e cria TODOS os secrets/variables no GitHub
> via `gh`, depois imprime a lista do que ainda falta você fazer ou enviar.
> Com `--push-develop` ele também dispara o Deploy DEV e com `--sync-main`
> registra os workflows no branch default (HOMOLOG). Detalhes:
> `./cd-setup-all.sh --help`.

---

## 0) Pré-requisitos na sua máquina

```bash
aws --version        # AWS CLI v2
gh --version         # GitHub CLI (opcional, mas recomendado)
docker --version
docker compose version   # precisa ser >= 2.24
```

Autentique:

```bash
aws configure        # ou export AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_REGION
gh auth login
```

---

## 1) Provisionar a AWS (uma vez por ambiente)

```bash
./scripts/aws/bootstrap-aws.sh --env dev
./scripts/aws/bootstrap-aws.sh --env homolog
```

O script (idempotente) cria/atualiza:

- ECR `raiji-app`;
- bucket S3 privado da CNH (`raiji-driver-docs-<conta>-<env>`);
- IAM role da EC2 (pull do ECR + S3 da CNH);
- IAM user `raiji-actions` (push/retag no ECR) e grava a access key em
  `~/.ssh/raiji-actions-access-key.json`;
- security group, key pair (`~/.ssh/raiji-<env>-pem`), Elastic IP e EC2
  Ubuntu 24.04 com Docker + Compose.

No final ele imprime **exatamente** os valores para o GitHub.

> **Custo:** 2 × `t3.small` + 2 Elastic IPs ≈ US$ 30–35/mês. Se quiser um só
> host, rode apenas `--env dev` e use o mesmo IP em `AWS_EC2_HOST_HOMOLOG`
> (portas 8000/9000 não conflitam). Para mudar o tipo, edite
> `infra/aws/aws-config.env` e rerode o bootstrap (resize automático).

---

## 2) Configurar o GitHub

Settings → Secrets and variables → Actions.

### 2.1 Variables (aba *Variables*)

| Nome | Valor |
|---|---|
| `AWS_EC2_HOST_DEV` | IP elástico impresso pelo bootstrap de `dev` |
| `AWS_EC2_HOST_HOMOLOG` | IP elástico impresso pelo bootstrap de `homolog` |

### 2.2 Secrets do repositório (aba *Secrets*)

| Nome | Valor |
|---|---|
| `AWS_ACCESS_KEY_ID` | `AccessKeyId` de `~/.ssh/raiji-actions-access-key.json` |
| `AWS_SECRET_ACCESS_KEY` | `SecretAccessKey` do mesmo arquivo |

### 2.3 Secrets por ambiente — crie como secrets de REPOSITÓRIO

**Por quê:** o job que chama o workflow reutilizável (`deploy-dev.yml`/
`deploy-homolog.yml`) **não pode declarar `environment:`** — limitação do
GitHub (o docs oficial nega a chave `environment` em job de chamada). Um
secret que exista APENAS no Environment chega vazio nesse job. O `deploy.yml`
tem hoje um **fallback** que também resolve secrets de Environment (os jobs
internos declaram `environment`), mas o **repositório é o local recomendado**:
funciona em 100% dos caminhos (preflight, deploy, rollback e Guardian).

Settings → Secrets and variables → Actions → *Secrets* (repositório):

| Nome | Valor |
|---|---|
| `AWS_SSH_KEY_DEV` / `AWS_SSH_KEY_HOMOLOG` | conteúdo de `~/.ssh/raiji-<env>-pem` |
| `DB_PASSWORD_DEV` / `DB_PASSWORD_HOMOLOG` | senha forte (pode gerar: `openssl rand -base64 24`) |
| `KEYCLOAK_ADMIN_CLIENT_SECRET_DEV` / `_HOMOLOG` | **exatamente** `raiji-admin-secret` (segredo commitado no realm) ou altere o realm antes do 1º deploy |
| `SMOKE_USERNAME_DEV` / `SMOKE_PASSWORD_DEV` | padrão `teste` / `teste123` (opcionais) |
| `SMOKE_USERNAME_HOMOLOG` / `SMOKE_PASSWORD_HOMOLOG` | idem — **troque em homolog** |

### 2.4 Environments `dev` e `homolog` (opcional — aprovação)

Settings → Environments → **New environment** (crie os dois, com os mesmos
nomes usados nos workflows).

- No `homolog`, ative **Required reviewers** para exigir aprovação manual
  antes do deploy do sistema.
- Se também criar *Environment secrets* com os mesmos nomes (`AWS_SSH_KEY_DEV`…),
  funciona — o `deploy.yml` resolve os dois escopos. **Não duplique valores
  diferentes** no repositório e no environment.

### 2.5 Secrets opcionais (repositório — funcionalidades)

Sem eles o deploy funciona, mas o recurso correspondente fica desligado:

| Secret | Recurso |
|---|---|
| `INFO_SIMPLES_KEY` | verificação de CNH (Infosimples) |
| `INFOSIMPLES_GOVBR_CPF` / `INFOSIMPLES_GOVBR_SENHA` | login gov.br da verificação |
| `RESEND_API_KEY` / `RESEND_FROM` | e-mails transacionais |
| `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` | caronas pagas / estorno |
| `RAIJI_CORS_ALLOWED_ORIGINS` | origens web extras (ex.: `https://app.seudominio.com`) |

> Esses valores são **repository secrets** (não de environment), porque o job
> que chama o workflow reutilizável não pode usar `environment:`.

---

## 3) Sincronizar o branch `main` (obrigatório uma vez)

Hoje o `main` está **83 commits atrás** do `develop` e **não tem
`.github/workflows`**. Por isso só aparecem `CI` e `Deploy DEV` no GitHub;
`Deploy HOMOLOG`, `Rollback (manual)` e `Guardian` só existem quando os
workflows chegam ao branch default.

```bash
git fetch origin
git log --oneline origin/main..origin/develop | head   # confirme que main é ancestral
git push origin origin/develop:main
```

> Isso dispara o `Deploy HOMOLOG`. Configure o environment `homolog` e o
> bootstrap antes. Se preferir, mude o default branch para `develop`
> (Settings → Branches) e faça o merge depois.

---

## 4) Disparar e validar

```bash
# dev
git push origin develop

# acompanhar
gh run list --limit 5
gh run watch
```

Fluxo esperado: `config` → `tests` → `build-push` (ECR) → `deploy` (EC2,
health-gate) → `smoke` (health/realm/token/me) → `release`.

Validação manual:

```bash
HOST=<IP elástico>
curl -fsS "http://$HOST:8000/actuator/health"
BASE_URL="http://$HOST:8000" ./scripts/smoke-test.sh --parallel
```

---

## 5) Se falhar — mensagens e causa

| Erro no Actions | Causa | Correção |
|---|---|---|
| `Credentials could not be loaded...` | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` vazios ou inválidos | Configure os repository secrets (2.2) |
| `Secret AWS_ACCESS_KEY_ID está vazio` | preflight novo detectou o problema | idem |
| `Variable AWS_EC2_HOST_DEV vazia` | bootstrap não rodou ou variable não criada | rode o bootstrap e crie a variable |
| `Secret AWS_SSH_KEY_DEV vazio no Environment 'dev'` | secret só existe no Environment (o job chamador não enxerga) ou não foi criado | crie como secret de REPOSITÓRIO (2.3); secret de Environment também funciona (fallback do deploy.yml) |
| `Segredo SSH_PRIVATE_KEY está vazio` | valor não chegou ao workflow reutilizável | idem — crie o secret no repositório e rode de novo |
| `Permission denied (publickey)` | `.pem` errada ou key pair recriado | use a `.pem` do bootstrap; não recrie o key pair |
| `docker compose ... !reset` / parse error | Compose < 2.24 na EC2 | `sudo apt-get install -y docker-compose-v2` |
| `pull da imagem falhou` | IAM user sem `ecr:GetAuthorizationToken`/`BatchGetImage` | rerode o bootstrap (reaplica a policy) |
| health-gate falhou no 1º deploy | Keycloak/Postgres ainda subindo | já aumentado para 15 min; veja `docker compose logs` na EC2 |
| smoke `token`/`me` falhou | usuário `teste` ausente ou senha diferente | ajuste `SMOKE_*` ou o realm |
| `Deploy HOMOLOG` não aparece | `main` sem workflows | passo 3 |

Logs da EC2:

```bash
ssh -i ~/.ssh/raiji-dev-pem ubuntu@<IP>
cd /opt/raiji/dev
docker compose -p raiji-dev -f docker-compose.yml -f docker-compose.aws.yml ps
docker compose -p raiji-dev -f docker-compose.yml -f docker-compose.aws.yml logs -f app keycloak kong-init
```

---

## 6) O que já está pronto no código

- Preflight nos callers: falha com mensagem clara se faltar secret/variable.
- `deploy.yml` valida credenciais AWS e segredos do ambiente antes de usar.
- **Fallback de escopo**: os secrets por ambiente funcionam como secret de
  REPOSITÓRIO (recomendado) OU no Environment `dev`/`homolog` — o
  `deploy.yml` declara `environment` e resolve os dois escopos.
- `docker-compose.aws.yml` usa `build: !reset null` (nunca tenta buildar o app
  na EC2) e passa todas as variáveis opcionais (CNH/Resend/Stripe/CORS/S3).
- `deploy.sh` grava `.env` com aspas seguras, cria o bucket padrão da CNH e
  envia RabbitMQ/Kong/Keycloak.
- `deploy-remote.sh` exige Compose ≥ 2.24, faz self-rollback também quando o
  `up` falha e espera até 15 min no primeiro boot.
- `bootstrap-aws.sh` cria Elastic IP, bucket S3 + permissões e IAM user do
  Actions (com access key local).
- `setup-cicd.sh` virou **somente verificação** (não sobrescreve mais nada).
- `check-github-config.sh` diagnostica a configuração do GitHub.
