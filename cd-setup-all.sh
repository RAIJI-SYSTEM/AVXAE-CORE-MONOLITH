#!/usr/bin/env bash
# =============================================================================
# cd-setup-all.sh — TUDO EM UM, NA RAIZ DO PROJETO.
#
# UM único arquivo que:
#   1. APPLY  — IMPLEMENTA todas as correções do CD DENTRO do projeto:
#               escreve (com backup) os workflows, scripts de deploy,
#               docker-compose.aws.yml e aws-config.env na versão correta.
#               Funciona em qualquer branch (main, develop, fix/...);
#   2. VERIFY — confere que as correções ficaram aplicadas;
#   3. AWS    — conecta na AWS (STS) e provisiona dev/homolog (bootstrap
#               idempotente: ECR, S3 da CNH, IAM, security group, key pair,
#               EC2 Ubuntu 24.04 com Docker, Elastic IP);
#   4. GITHUB — cria secrets/variables/environments no GitHub Actions via gh
#               (AWS, SSH, DB, Keycloak, smoke e funcionais opcionais);
#   5. PUSH   — commita as correções aplicadas e publica a branch atual
#               (padrão: NADA vai para develop/main — só com --push-develop,
#               que pede confirmação, dispara o Deploy DEV e acompanha o run);
#   6. REPORT — resumo do que foi feito + "O QUE VOCÊ PRECISA FAZER".
#
# COMO RODAR (na RAIZ do projeto):
#   ./cd-setup-all.sh                         # aplica tudo + aws + github + push da branch atual
#   ./cd-setup-all.sh --push-develop --watch  # publicar develop + Deploy DEV (pede confirmação)
#   ./cd-setup-all.sh --sync-main             # + push develop:main (registra workflows, HOMOLOG)
#   ./cd-setup-all.sh --dry-run               # pré-visualiza sem alterar nada
#   ./cd-setup-all.sh --only apply            # executa apenas uma fase
#   ./cd-setup-all.sh --envs dev              # só o ambiente dev
#
# Credenciais (flags OU variáveis de ambiente):
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY   (ou --aws-key / --aws-secret)
#       Permissões EC2 + IAM + ECR + S3. Sem elas, a fase AWS é pulada e
#       listada no relatório (nada quebra). Alternativa: --aws-profile.
#   GH_TOKEN                                    (ou --gh-token)
#       Token com ADMIN do repositório (cria secrets/variables/environments).
#
# Senhas/segredos (opcionais — o que faltar é gerado e salvo localmente):
#   --db-password-dev / --db-password-homolog   (ou env DB_PASSWORD_DEV/HOMOLOG)
#   --keycloak-secret <s>   padrão 'raiji-admin-secret' (= realm importado)
#   --smoke-user / --smoke-pass                 padrão teste/teste123 (= realm)
# Funcionais opcionais (env): INFO_SIMPLES_KEY, INFOSIMPLES_GOVBR_CPF,
#   INFOSIMPLES_GOVBR_SENHA, RESEND_API_KEY, RESEND_FROM, STRIPE_API_KEY,
#   STRIPE_WEBHOOK_SECRET, RAIJI_CORS_ALLOWED_ORIGINS.
#
# Arquivos gerados (NUNCA commitados — .gitignore cobre .cd-secrets-local/):
#   .cd-secrets-local/cd-secrets-<ts>.env    segredos gerados/consultados
#   .cd-secrets-local/backup-<ts>/           versão antiga de cada arquivo alterado
#   .cd-secrets-local/bootstrap-<env>-<ts>.log  log completo do bootstrap
# =============================================================================
set -uo pipefail

# ------------------------------------------------------------------ helpers --
DRY_RUN=0
C_OK='' C_BAD='' C_WARN='' C_INFO='' C_DIM='' C_NC=''
if [[ -t 1 ]]; then
  C_OK=$'\033[32m'; C_BAD=$'\033[31m'; C_WARN=$'\033[33m'
  C_INFO=$'\033[36m'; C_DIM=$'\033[2m'; C_NC=$'\033[0m'
fi
phase() { printf '\n%s════ FASE %s · %s ════%s\n' "$C_INFO" "$1" "$2" "$C_NC"; }
ok()    { printf '  %sOK%s    %s\n' "$C_OK" "$C_NC" "$*"; }
bad()   { printf '  %sFALTA%s %s\n' "$C_BAD" "$C_NC" "$*"; }
warn()  { printf '  %sAVISO%s %s\n' "$C_WARN" "$C_NC" "$*"; }
info()  { printf '  %s·%s %s\n' "$C_DIM" "$C_NC" "$*"; }
step()  { printf '  %s»%s %s\n' "$C_INFO" "$C_NC" "$*"; }

DONE_LIST=(); MISSING_LIST=(); DECISIONS_LIST=()
done_item()   { DONE_LIST+=("$1"); ok "$1"; }
missing_item(){ MISSING_LIST+=("$1"); bad "$1"; }
decision()    { DECISIONS_LIST+=("$1"); }

exec_cmd() { # argv exato, sem shell
  if [[ "$DRY_RUN" == 1 ]]; then info "[dry-run] $*"; return 0; fi
  "$@"
}

# ------------------------------------------------- raiz do projeto e opções --
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# O script roda na RAIZ do projeto. Se estiver em scripts/ (legado), sobe um nível.
if [[ -f "$SCRIPT_DIR/pom.xml" || -f "$SCRIPT_DIR/docker-compose.yml" || -d "$SCRIPT_DIR/raiji-app" ]]; then
  ROOT="$SCRIPT_DIR"
else
  ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
cd "$ROOT" || exit 1

usage() {
  cat <<'USO'
cd-setup-all.sh — TUDO EM UM, na raiz do projeto.

Uso:
  ./cd-setup-all.sh                         aplica correções + AWS + GitHub + push da branch atual
                                            (por padrão NADA vai para develop/main)
  ./cd-setup-all.sh --push-develop --watch  PUBLICA develop e dispara o Deploy DEV
                                            (pede confirmação; --yes pula a pergunta)
  ./cd-setup-all.sh --sync-main             também envia develop:main (HOMOLOG + workflows visíveis)
  ./cd-setup-all.sh --envs dev              provisiona só um ambiente (dev|homolog)
  ./cd-setup-all.sh --only apply            roda só uma fase (apply|verify|aws|github|push)
  ./cd-setup-all.sh --skip aws              pula fases
  ./cd-setup-all.sh --dry-run               pré-visualiza tudo, não altera nada

Credenciais:
  --aws-key ID --aws-secret SECRET   (ou env AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY)
  --aws-profile PERFIL               alternativa via ~/.aws/credentials
  --gh-token TOKEN                   (ou env GH_TOKEN) admin do repositório
  --repo DONO/NOME                   se o remote 'origin' não estiver configurado

Segredos:
  --db-password-dev/--db-password-homolog SENHA   (ou env; senão gera e salva em .cd-secrets-local/)
  --keycloak-secret S                padrão 'raiji-admin-secret' (= realm importado)
  --smoke-user U --smoke-pass P      padrão teste/teste123 (= realm importado)
  --actions-key-file CAMINHO         access key do IAM 'raiji-actions'
                                     (padrão ~/.ssh/raiji-actions-access-key.json)

Funcionais opcionais (env antes de rodar): INFO_SIMPLES_KEY,
INFOSIMPLES_GOVBR_CPF, INFOSIMPLES_GOVBR_SENHA, RESEND_API_KEY,
RESEND_FROM, STRIPE_API_KEY, STRIPE_WEBHOOK_SECRET,
RAIJI_CORS_ALLOWED_ORIGINS.
USO
  exit 0
}

ENVS="dev homolog"
ONLY_PHASE="" SKIP_PHASES=""
AWS_KEY=""; AWS_SECRET=""; AWS_PROFILE_OPT=""
GH_TOKEN_OPT=""
REPO_OPT=""
PUSH_DEVELOP=0; SYNC_MAIN=0; WATCH=0
DB_PASS_DEV="${DB_PASSWORD_DEV:-}"; DB_PASS_HOMOLOG="${DB_PASSWORD_HOMOLOG:-}"
KC_SECRET="${KEYCLOAK_ADMIN_CLIENT_SECRET:-raiji-admin-secret}"
SMOKE_USER="${SMOKE_USERNAME:-teste}"; SMOKE_PASS="${SMOKE_PASSWORD:-teste123}"
ACTIONS_KEY_FILE="${ACTIONS_KEY_FILE:-$HOME/.ssh/raiji-actions-access-key.json}"
OPTIONAL_SECRETS=(INFO_SIMPLES_KEY INFOSIMPLES_GOVBR_CPF INFOSIMPLES_GOVBR_SENHA \
                  RESEND_API_KEY RESEND_FROM STRIPE_API_KEY STRIPE_WEBHOOK_SECRET \
                  RAIJI_CORS_ALLOWED_ORIGINS)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --envs) ENVS="$2"; shift 2 ;;
    --only) ONLY_PHASE="$2"; shift 2 ;;
    --skip) SKIP_PHASES="$2"; shift 2 ;;
    --aws-key) AWS_KEY="$2"; shift 2 ;;
    --aws-secret) AWS_SECRET="$2"; shift 2 ;;
    --aws-profile) AWS_PROFILE_OPT="$2"; shift 2 ;;
    --gh-token) GH_TOKEN_OPT="$2"; shift 2 ;;
    --repo) REPO_OPT="$2"; shift 2 ;;
    --db-password-dev) DB_PASS_DEV="$2"; shift 2 ;;
    --db-password-homolog) DB_PASS_HOMOLOG="$2"; shift 2 ;;
    --keycloak-secret) KC_SECRET="$2"; shift 2 ;;
    --smoke-user) SMOKE_USER="$2"; shift 2 ;;
    --smoke-pass) SMOKE_PASS="$2"; shift 2 ;;
    --actions-key-file) ACTIONS_KEY_FILE="$2"; shift 2 ;;
    --push-develop) PUSH_DEVELOP=1; shift ;;
    --yes) ASSUME_YES=1; shift ;;
    --sync-main) SYNC_MAIN=1; shift ;;
    --watch) WATCH=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage ;;
    *) echo "opção inválida: $1 (use --help)" >&2; exit 2 ;;
  esac
done
ASSUME_YES="${ASSUME_YES:-0}"

for e in $ENVS; do
  [[ "$e" =~ ^(dev|homolog)$ ]] || { echo "--envs aceita só dev e/ou homolog" >&2; exit 2; }
done

# roda NA RAIZ do projeto (validação após --help para que a ajuda funcione em qualquer lugar)
if [[ ! -f "$ROOT/pom.xml" && ! -f "$ROOT/docker-compose.yml" && ! -d "$ROOT/raiji-app" ]]; then
  echo "ERRO: este script deve rodar na RAIZ do projeto raiji (não encontrei pom.xml/docker-compose.yml/raiji-app em $ROOT)." >&2
  echo "Copie cd-setup-all.sh para a raiz do projeto e rode: ./cd-setup-all.sh" >&2
  exit 2
fi

phase_wanted() { # fase $1 deve rodar?
  local p="$1"
  if [[ -n "$ONLY_PHASE" && "$ONLY_PHASE" != "$p" ]]; then return 1; fi
  if [[ " $SKIP_PHASES " == *" $p "* ]]; then return 1; fi
  return 0
}

STAMP="$(date +%Y%m%d-%H%M%S)"
SECRETS_DIR="$ROOT/.cd-secrets-local"
mkdir -p "$SECRETS_DIR"; chmod 700 "$SECRETS_DIR" 2>/dev/null || true
SECRETS_FILE="$SECRETS_DIR/cd-secrets-$STAMP.env"
touch "$SECRETS_FILE"; chmod 600 "$SECRETS_FILE"

region_of() { grep -E '^AWS_REGION=' infra/aws/aws-config.env 2>/dev/null | cut -d= -f2 | tr -d ' "' || true; }
AWS_REGION_CFG="$(region_of)"; AWS_REGION_CFG="${AWS_REGION_CFG:-us-east-1}"
gen_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24
  else
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24
  fi
}
save_secret_line() {
  if [[ "$DRY_RUN" == 0 ]]; then printf '%s\n' "$1" >> "$SECRETS_FILE"; fi
}

# =============================================================================
# PAYLOAD — conteúdo embutido dos arquivos corrigidos do CD.
# Gerado a partir do branch fix/cd-deploy-hardening. A fase APPLY compara
# com o que existe no projeto e escreve (com backup) o que estiver diferente.
# =============================================================================

PAYLOAD_FILES=(
  ".github/workflows/ci.yml"
  ".github/workflows/deploy.yml"
  ".github/workflows/deploy-dev.yml"
  ".github/workflows/deploy-homolog.yml"
  ".github/workflows/rollback.yml"
  ".github/workflows/guardian.yml"
  "scripts/smoke-test.sh"
  "scripts/setup-cicd.sh"
  "scripts/aws/bootstrap-aws.sh"
  "scripts/aws/deploy.sh"
  "scripts/aws/deploy-remote.sh"
  "scripts/aws/rollback.sh"
  "scripts/aws/guardian.sh"
  "scripts/aws/ecr-retag.sh"
  "scripts/aws/check-github-config.sh"
  "docker-compose.aws.yml"
  "infra/aws/aws-config.env"
  "docs/CD-CHECKLIST.md"
  "docs/DEPLOY.md"
)

payload_stage() { # $1 = diretório de destino do payload
  local dst="$1"
  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/ci.yml" <<'CD_SETUP_EOF_00_899ce9c2'
# =============================================================================
# CI — roda em TODO Pull Request e em push na main/develop.
# Trilhas paralelas (fail-fast: false -> uma não cancela a outra):
#   1. unit-tests    -> matriz DINÂMICA por módulo (módulos novos entram sozinhos)
#   2. verify        -> pipeline da issue: checkout -> JDK 21 -> cache Maven ->
#                       mvn clean verify (unitários + integração + cobertura) ->
#                       upload do relatório JaCoCo
#   3. docker-image  -> garante que a imagem do deploy continua buildando
# =============================================================================
name: CI

on:
  pull_request:
  push:
    branches: [main, develop]
  workflow_dispatch:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

env:
  MAVEN_ARGS: "-B -ntp -T 1C"

jobs:
  # ---------------------------------------------------------------------------
  # Detecta os módulos lendo o pom.xml raiz -> a matriz cresce automaticamente
  # quando um módulo novo for criado (testes futuros rodam em paralelo sem
  # precisar editar este workflow).
  # ---------------------------------------------------------------------------
  detect-modules:
    name: Detectar módulos (matriz dinâmica)
    runs-on: ubuntu-latest
    outputs:
      modules: ${{ steps.list.outputs.modules }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Listar módulos do pom.xml
        id: list
        run: |
          MODULES="$(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' pom.xml | jq -R . | jq -sc .)"
          echo "modules=$MODULES" >> "$GITHUB_OUTPUT"
          echo "Módulos detectados: $MODULES"

  # ---------------------------------------------------------------------------
  # Testes unitários em PARALELO (um job por módulo) — feedback rápido.
  # ---------------------------------------------------------------------------
  unit-tests:
    name: Unitários · ${{ matrix.module }}
    needs: detect-modules
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        module: ${{ fromJSON(needs.detect-modules.outputs.modules) }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 21 + cache Maven
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: Compilar dependências do módulo (sem testes)
        run: mvn ${{ env.MAVEN_ARGS }} -pl ${{ matrix.module }} -am install -DskipTests -Djacoco.skip=true

      - name: Testes unitários do módulo (${{ matrix.module }})
        run: mvn ${{ env.MAVEN_ARGS }} -pl ${{ matrix.module }} surefire:test -Djacoco.skip=true

      - name: Upload relatórios surefire (${{ matrix.module }})
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-${{ matrix.module }}
          path: ${{ matrix.module }}/target/surefire-reports/
          if-no-files-found: ignore
          retention-days: 7

  # ---------------------------------------------------------------------------
  # Pipeline exigido pela issue: build + testes + cobertura (gate do PR).
  # ---------------------------------------------------------------------------
  verify:
    name: Build + testes + cobertura (mvn clean verify)
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 21 + cache Maven
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: mvn clean verify (unitários + integração + JaCoCo >= 80%)
        run: mvn ${{ env.MAVEN_ARGS }} clean verify

      - name: Upload relatório JaCoCo
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: |
            **/target/site/jacoco/
            **/target/site/jacoco-aggregate/
          if-no-files-found: warn
          retention-days: 14

      - name: Upload relatórios de teste (surefire + failsafe)
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: |
            **/target/surefire-reports/*.xml
            **/target/failsafe-reports/*.xml
          if-no-files-found: ignore
          retention-days: 14

  # ---------------------------------------------------------------------------
  # Build da imagem Docker (paralelo) — o que o CD publica no ECR.
  # ---------------------------------------------------------------------------
  docker-image:
    name: Imagem Docker (build de verificação)
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build Dockerfile.app (sem push)
        uses: docker/build-push-action@v6
        with:
          context: .
          file: Dockerfile.app
          push: false
          cache-from: type=gha,scope=ci
          cache-to: type=gha,mode=max,scope=ci
CD_SETUP_EOF_00_899ce9c2

  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/deploy.yml" <<'CD_SETUP_EOF_01_f130ad0d'
# =============================================================================
# Deploy reutilizável — AWS (ECR + EC2 + docker compose).
# Chamado por deploy-dev.yml (develop) e deploy-homolog.yml (main).
#
# CONFIGURAÇÃO FÁCIL: região e portas vêm de infra/aws/aws-config.env
# (job `config`). Para mudar o TIPO DE MÁQUINA, edite INSTANCE_TYPE_* no
# mesmo arquivo e rerode scripts/aws/bootstrap-aws.sh (resize automático).
#
# Fluxo (proteção de release — versão quebrada nunca fica no ar):
#   config -> tests (unitários)
#     -> build-push (ECR: <env>-<sha> + <env>-current; preserva <env>-previous)
#     -> deploy (SSH + deploy-remote.sh: health-gate + self-rollback;
#                SEM versão anterior -> app é PARADO, nada quebrado é servido)
#     -> smoke (MATRIZ PARALELA health/realm/token/me — falha BLOQUEIA release)
#     -> release (gate final + URL pública)
#     -> rollback (automático: retag <env>-previous + redeploy + smoke)
# =============================================================================
name: Deploy (reutilizável · AWS)

on:
  workflow_call:
    inputs:
      environment:
        description: "Ambiente alvo: dev | homolog"
        required: true
        type: string
      ec2-host:
        description: "Host/IP público da EC2 do ambiente (variável, não segredo)"
        required: true
        type: string
      ec2-user:
        description: "Usuário SSH da EC2"
        required: false
        type: string
        default: ubuntu
    secrets:
      AWS_ACCESS_KEY_ID:
        required: true
      AWS_SECRET_ACCESS_KEY:
        required: true
      SSH_PRIVATE_KEY:
        required: true
      DB_PASSWORD:
        required: true
      KEYCLOAK_ADMIN_CLIENT_SECRET:
        required: true
      SMOKE_USERNAME:
        required: false
      SMOKE_PASSWORD:
        required: false
      # --- Escopo duplo (fallback): se o caller passar vazio (secret só no
      # Environment), os jobs que declaram `environment` resolvem direto o
      # secret com sufixo do ambiente. Declaração opcional: nem sempre o
      # caller os passa.
      AWS_SSH_KEY_DEV:
        required: false
      AWS_SSH_KEY_HOMOLOG:
        required: false
      DB_PASSWORD_DEV:
        required: false
      DB_PASSWORD_HOMOLOG:
        required: false
      KEYCLOAK_ADMIN_CLIENT_SECRET_DEV:
        required: false
      KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG:
        required: false
      SMOKE_USERNAME_DEV:
        required: false
      SMOKE_PASSWORD_DEV:
        required: false
      SMOKE_USERNAME_HOMOLOG:
        required: false
      SMOKE_PASSWORD_HOMOLOG:
        required: false
      # --- Opcionais: funcionalidades da aplicação (vazio = desligado) --------
      INFO_SIMPLES_KEY:
        required: false
      INFOSIMPLES_GOVBR_CPF:
        required: false
      INFOSIMPLES_GOVBR_SENHA:
        required: false
      RESEND_API_KEY:
        required: false
      RESEND_FROM:
        required: false
      STRIPE_API_KEY:
        required: false
      STRIPE_WEBHOOK_SECRET:
        required: false
      RAIJI_CORS_ALLOWED_ORIGINS:
        required: false
      DRIVER_DOCS_BUCKET:
        required: false
      RAIJI_PLATFORM_FEE_PERCENT:
        required: false
      RAIJI_TIME_ZONE:
        required: false

concurrency:
  group: deploy-${{ inputs.environment }}
  cancel-in-progress: false

permissions:
  contents: read

env:
  ECR_REPOSITORY: raiji-app
  IMAGE_TAG: ${{ inputs.environment }}-${{ github.sha }}

jobs:
  # ---------------------------------------------------------------------------
  # Lê infra/aws/aws-config.env — fonte única da configuração AWS (região,
  # portas). Trocou o arquivo, trocou o deploy: nenhum valor fica hardcoded.
  # ---------------------------------------------------------------------------
  config:
    name: Config AWS (aws-config.env)
    runs-on: ubuntu-latest
    timeout-minutes: 5
    outputs:
      aws-region: ${{ steps.cfg.outputs.aws-region }}
      base-port: ${{ steps.cfg.outputs.base-port }}
      kc-port: ${{ steps.cfg.outputs.kc-port }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Ler infra/aws/aws-config.env
        id: cfg
        env:
          ENVIRONMENT: ${{ inputs.environment }}
        run: |
          [[ -f infra/aws/aws-config.env ]] || { echo "::error::infra/aws/aws-config.env não encontrado"; exit 1; }
          set -a; . infra/aws/aws-config.env; set +a
          SUFFIX="$(echo "$ENVIRONMENT" | tr '[:lower:]' '[:upper:]')"
          bp_var="BASE_PORT_$SUFFIX"; kp_var="KC_PORT_$SUFFIX"
          BASE_PORT_VALUE="${!bp_var:?BASE_PORT_$SUFFIX não definido em infra/aws/aws-config.env}"
          KC_PORT_VALUE="${!kp_var:-$((BASE_PORT_VALUE + 180))}"
          {
            echo "aws-region=${AWS_REGION:-us-east-1}"
            echo "base-port=$BASE_PORT_VALUE"
            echo "kc-port=$KC_PORT_VALUE"
          } >> "$GITHUB_OUTPUT"

  # ---------------------------------------------------------------------------
  # Porta de entrada: testes unitários rápidos (integração/cobertura ficam no CI).
  # ---------------------------------------------------------------------------
  tests:
    name: Testes rápidos (unitários)
    runs-on: ubuntu-latest
    timeout-minutes: 25
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 21 + cache Maven
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: mvn test (paralelo por threads do Maven)
        run: mvn -B -ntp -T 1C test -Djacoco.skip=true

  # ---------------------------------------------------------------------------
  # Build + push da imagem no ECR. Antes de sobrescrever a tag <env>-current,
  # a imagem em produção é preservada como <env>-previous (base do rollback).
  # ---------------------------------------------------------------------------
  build-push:
    name: Build & push ECR (${{ inputs.environment }})
    needs: [config, tests]
    runs-on: ubuntu-latest
    timeout-minutes: 45
    environment: ${{ inputs.environment }}
    outputs:
      registry: ${{ steps.ecr.outputs.registry }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Validar credenciais AWS
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
        run: |
          missing=0
          [[ -n "$AWS_ACCESS_KEY_ID" ]] || {
            echo "::error::Secret AWS_ACCESS_KEY_ID está vazio. Configure em Settings -> Secrets and variables -> Actions (repositório)."
            missing=1
          }
          [[ -n "$AWS_SECRET_ACCESS_KEY" ]] || {
            echo "::error::Secret AWS_SECRET_ACCESS_KEY está vazio. Configure em Settings -> Secrets and variables -> Actions (repositório)."
            missing=1
          }
          exit "$missing"

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ needs.config.outputs.aws-region }}

      - name: Login no ECR
        id: ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Preservar imagem atual como '<env>-previous' (rollback)
        env:
          REGISTRY: ${{ steps.ecr.outputs.registry }}
          AWS_REGION_NAME: ${{ needs.config.outputs.aws-region }}
          ENVIRONMENT: ${{ inputs.environment }}
        run: |
          ./scripts/aws/ecr-retag.sh \
            "$REGISTRY/$ECR_REPOSITORY" "${ENVIRONMENT}-current" "${ENVIRONMENT}-previous" \
            --region "$AWS_REGION_NAME" --if-missing=skip

      - name: Setup Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build & push (tags <env>-<sha> e <env>-current)
        uses: docker/build-push-action@v6
        with:
          context: .
          file: Dockerfile.app
          push: true
          tags: |
            ${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}:${{ env.IMAGE_TAG }}
            ${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}:${{ inputs.environment }}-current
          cache-from: type=gha,scope=app-${{ inputs.environment }}
          cache-to: type=gha,mode=max,scope=app-${{ inputs.environment }}

  # ---------------------------------------------------------------------------
  # Deploy na EC2: sincroniza arquivos, grava .env (variáveis do provedor) e
  # recria o stack. O script remoto faz health-gate + self-rollback local;
  # sem versão anterior, PARA o app (não serve versão quebrada).
  # ---------------------------------------------------------------------------
  deploy:
    name: Deploy EC2 (${{ inputs.environment }})
    needs: [build-push, config]
    runs-on: ubuntu-latest
    timeout-minutes: 40
    environment: ${{ inputs.environment }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Validar segredos do ambiente
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
          KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET }}
        run: |
          missing=0
          for name in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY SSH_PRIVATE_KEY DB_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET; do
            if [[ -z "${!name}" ]]; then
              echo "::error::Segredo $name está vazio. Veja docs/CD-CHECKLIST.md (seção 'GitHub')."
              missing=1
            fi
          done
          exit "$missing"

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ needs.config.outputs.aws-region }}

      - name: Deploy via SSH (scripts/aws/deploy.sh)
        env:
          ENVIRONMENT: ${{ inputs.environment }}
          EC2_HOST: ${{ inputs.ec2-host }}
          EC2_USER: ${{ inputs.ec2-user }}
          # Escopo duplo (fallback): o secret passado pelo caller (REPOSITÓRIO)
          # tem prioridade; se vier vazio (secret só no Environment), os jobs
          # daqui — que declaram `environment` — resolvem direto o secret com
          # sufixo do ambiente (AWS_SSH_KEY_DEV / AWS_SSH_KEY_HOMOLOG, ...).
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY || secrets.AWS_SSH_KEY_DEV || secrets.AWS_SSH_KEY_HOMOLOG }}
          AWS_REGION_NAME: ${{ needs.config.outputs.aws-region }}
          BASE_PORT: ${{ needs.config.outputs.base-port }}
          KC_PORT: ${{ needs.config.outputs.kc-port }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD || secrets.DB_PASSWORD_DEV || secrets.DB_PASSWORD_HOMOLOG }}
          KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET || secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_DEV || secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG }}
          # Funcionalidades opcionais (vazio = recurso desligado)
          INFO_SIMPLES_KEY: ${{ secrets.INFO_SIMPLES_KEY }}
          INFOSIMPLES_GOVBR_CPF: ${{ secrets.INFOSIMPLES_GOVBR_CPF }}
          INFOSIMPLES_GOVBR_SENHA: ${{ secrets.INFOSIMPLES_GOVBR_SENHA }}
          RESEND_API_KEY: ${{ secrets.RESEND_API_KEY }}
          RESEND_FROM: ${{ secrets.RESEND_FROM }}
          STRIPE_API_KEY: ${{ secrets.STRIPE_API_KEY }}
          STRIPE_WEBHOOK_SECRET: ${{ secrets.STRIPE_WEBHOOK_SECRET }}
          RAIJI_CORS_ALLOWED_ORIGINS: ${{ secrets.RAIJI_CORS_ALLOWED_ORIGINS }}
          DRIVER_DOCS_BUCKET: ${{ secrets.DRIVER_DOCS_BUCKET }}
          RAIJI_PLATFORM_FEE_PERCENT: ${{ secrets.RAIJI_PLATFORM_FEE_PERCENT }}
          RAIJI_TIME_ZONE: ${{ secrets.RAIJI_TIME_ZONE }}
        run: ./scripts/aws/deploy.sh

  # ---------------------------------------------------------------------------
  # Smoke tests da issue — MATRIZ PARALELA (4 jobs simultâneos, fail-fast:false):
  #   health -> GET  /actuator/health  == {"status":"UP"}
  #   realm  -> GET  /realms/raiji     == 200
  #   token  -> POST /realms/raiji/protocol/openid-connect/token == 200
  #   me     -> GET  /api/me com token == 200
  # Qualquer falha BLOQUEIA o release (job `release` exige smoke verde) e
  # dispara o rollback automático.
  # ---------------------------------------------------------------------------
  smoke:
    name: Smoke · ${{ matrix.check }}
    needs: [deploy, config]
    runs-on: ubuntu-latest
    timeout-minutes: 15
    environment: ${{ inputs.environment }}
    env:
      BASE_URL: http://${{ inputs.ec2-host }}:${{ needs.config.outputs.base-port }}
    strategy:
      fail-fast: false
      matrix:
        check: [health, realm, token, me]
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Smoke check '${{ matrix.check }}' em ${{ env.BASE_URL }}
        env:
          BASE_URL: ${{ env.BASE_URL }}
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME || secrets.SMOKE_USERNAME_DEV || secrets.SMOKE_USERNAME_HOMOLOG || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD || secrets.SMOKE_PASSWORD_DEV || secrets.SMOKE_PASSWORD_HOMOLOG || 'teste123' }}
        run: ./scripts/smoke-test.sh --check "${{ matrix.check }}" --retries 18 --retry-delay 10

  # ---------------------------------------------------------------------------
  # Release gate: só roda com TUDO verde; publica a URL acessível no sumário.
  # ---------------------------------------------------------------------------
  release:
    name: Release (${{ inputs.environment }}) ✅
    needs: [build-push, deploy, smoke, config]
    if: success()
    runs-on: ubuntu-latest
    timeout-minutes: 5
    environment: ${{ inputs.environment }}
    steps:
      - name: Publicar URL do ambiente
        run: |
          {
            echo "## ✅ Release ${{ inputs.environment }} publicado"
            echo ""
            echo "| Item | Valor |"
            echo "|---|---|"
            echo "| URL (Kong) | http://${{ inputs.ec2-host }}:${{ needs.config.outputs.base-port }} |"
            echo "| Health | http://${{ inputs.ec2-host }}:${{ needs.config.outputs.base-port }}/actuator/health |"
            echo "| Imagem | ${IMAGE_TAG} |"
            echo "| Smoke tests | health ✔ realm ✔ token ✔ me ✔ (paralelos) |"
          } >> "$GITHUB_STEP_SUMMARY"

  # ---------------------------------------------------------------------------
  # Rollback AUTOMÁTICO: se o deploy/smoke falharem e EXISTIR versão anterior,
  # retaga <env>-previous -> <env>-current e refaz o deploy. Sem anterior
  # (1º deploy), o rollback.sh para o app e o ambiente fica offline até uma
  # versão correta ser publicada.
  # ---------------------------------------------------------------------------
  rollback:
    name: Rollback automático (${{ inputs.environment }})
    needs: [deploy, smoke, release, config]
    if: failure() && needs.deploy.result != 'skipped'
    runs-on: ubuntu-latest
    timeout-minutes: 40
    environment: ${{ inputs.environment }}
    env:
      BASE_URL: http://${{ inputs.ec2-host }}:${{ needs.config.outputs.base-port }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ needs.config.outputs.aws-region }}

      - name: Rollback (retag da imagem anterior + redeploy + smoke)
        env:
          ENVIRONMENT: ${{ inputs.environment }}
          EC2_HOST: ${{ inputs.ec2-host }}
          EC2_USER: ${{ inputs.ec2-user }}
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY || secrets.AWS_SSH_KEY_DEV || secrets.AWS_SSH_KEY_HOMOLOG }}
          AWS_REGION_NAME: ${{ needs.config.outputs.aws-region }}
          BASE_PORT: ${{ needs.config.outputs.base-port }}
          BASE_URL: ${{ env.BASE_URL }}
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME || secrets.SMOKE_USERNAME_DEV || secrets.SMOKE_USERNAME_HOMOLOG || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD || secrets.SMOKE_PASSWORD_DEV || secrets.SMOKE_PASSWORD_HOMOLOG || 'teste123' }}
        run: ./scripts/aws/rollback.sh --auto
CD_SETUP_EOF_01_f130ad0d

  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/deploy-dev.yml" <<'CD_SETUP_EOF_02_8f4657f8'
# =============================================================================
# CD DEV — push na branch `develop` atualiza o ambiente de DESENVOLVIMENTO (dev)
# na AWS. Região/portas definidos em infra/aws/aws-config.env.
# Segredos/variáveis: ver docs/CD-CHECKLIST.md e docs/DEPLOY.md.
# =============================================================================
name: Deploy DEV (develop -> AWS dev)

on:
  push:
    branches: [develop]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  # ---------------------------------------------------------------------------
  # Falha CEDO e com mensagem clara quando falta configuração no GitHub.
  # Sem isso o erro aparece lá no `configure-aws-credentials` como
  # "Credentials could not be loaded" — difícil de diagnosticar.
  # ---------------------------------------------------------------------------
  preflight:
    name: Pré-requisitos (dev)
    runs-on: ubuntu-latest
    timeout-minutes: 5
    environment: dev
    steps:
      - name: Validar secrets e variables do GitHub
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          EC2_HOST: ${{ vars.AWS_EC2_HOST_DEV }}
          SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_DEV }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD_DEV }}
          KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_DEV }}
        run: |
          missing=0
          check() {
            if [[ -z "${!1}" ]]; then
              echo "::error::$2"
              missing=1
            fi
          }
          check AWS_ACCESS_KEY_ID      "Secret AWS_ACCESS_KEY_ID vazio (Settings -> Secrets and variables -> Actions -> Secrets)."
          check AWS_SECRET_ACCESS_KEY  "Secret AWS_SECRET_ACCESS_KEY vazio (Settings -> Secrets and variables -> Actions -> Secrets)."
          check EC2_HOST               "Variable AWS_EC2_HOST_DEV vazia (Settings -> Secrets and variables -> Actions -> Variables). Rode ./scripts/aws/bootstrap-aws.sh --env dev."
          check SSH_PRIVATE_KEY        "Secret AWS_SSH_KEY_DEV vazio. Crie como secret de REPOSITÓRIO (Settings -> Secrets and variables -> Actions -> Secrets, conteúdo da .pem do bootstrap) ou no Environment 'dev'."
          check DB_PASSWORD            "Secret DB_PASSWORD_DEV vazio. Crie como secret de REPOSITÓRIO ou no Environment 'dev'."
          check KEYCLOAK_ADMIN_CLIENT_SECRET "Secret KEYCLOAK_ADMIN_CLIENT_SECRET_DEV vazio. Crie como secret de REPOSITÓRIO ou no Environment 'dev' (use exatamente o segredo do client raiji-admin)."
          exit "$missing"

  deploy-dev:
    name: dev
    needs: preflight
    uses: ./.github/workflows/deploy.yml
    with:
      environment: dev
      ec2-host: ${{ vars.AWS_EC2_HOST_DEV }}
    secrets:
      AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
      AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
      SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_DEV }}
      DB_PASSWORD: ${{ secrets.DB_PASSWORD_DEV }}
      KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_DEV }}
      SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME_DEV }}
      SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD_DEV }}
      # Opcionais: funcionalidades da aplicação (vazio = desligado)
      INFO_SIMPLES_KEY: ${{ secrets.INFO_SIMPLES_KEY }}
      INFOSIMPLES_GOVBR_CPF: ${{ secrets.INFOSIMPLES_GOVBR_CPF }}
      INFOSIMPLES_GOVBR_SENHA: ${{ secrets.INFOSIMPLES_GOVBR_SENHA }}
      RESEND_API_KEY: ${{ secrets.RESEND_API_KEY }}
      RESEND_FROM: ${{ secrets.RESEND_FROM }}
      STRIPE_API_KEY: ${{ secrets.STRIPE_API_KEY }}
      STRIPE_WEBHOOK_SECRET: ${{ secrets.STRIPE_WEBHOOK_SECRET }}
      RAIJI_CORS_ALLOWED_ORIGINS: ${{ secrets.RAIJI_CORS_ALLOWED_ORIGINS }}
      DRIVER_DOCS_BUCKET: ${{ secrets.DRIVER_DOCS_BUCKET }}
      RAIJI_PLATFORM_FEE_PERCENT: ${{ secrets.RAIJI_PLATFORM_FEE_PERCENT }}
      RAIJI_TIME_ZONE: ${{ secrets.RAIJI_TIME_ZONE }}
CD_SETUP_EOF_02_8f4657f8

  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/deploy-homolog.yml" <<'CD_SETUP_EOF_03_ba847826'
# =============================================================================
# CD SISTEMA — push na branch `main` atualiza o ambiente de HOMOLOGAÇÃO/SISTEMA
# (homolog) na AWS. Região/portas definidos em infra/aws/aws-config.env.
# Dica: configure "Required reviewers" no Environment `homolog` para exigir
# aprovação manual antes do deploy do sistema (docs/DEPLOY.md).
# =============================================================================
name: Deploy HOMOLOG (main -> AWS sistema)

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  # ---------------------------------------------------------------------------
  # Falha CEDO e com mensagem clara quando falta configuração no GitHub.
  # ---------------------------------------------------------------------------
  preflight:
    name: Pré-requisitos (homolog)
    runs-on: ubuntu-latest
    timeout-minutes: 5
    environment: homolog
    steps:
      - name: Validar secrets e variables do GitHub
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          EC2_HOST: ${{ vars.AWS_EC2_HOST_HOMOLOG }}
          SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_HOMOLOG }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD_HOMOLOG }}
          KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG }}
        run: |
          missing=0
          check() {
            if [[ -z "${!1}" ]]; then
              echo "::error::$2"
              missing=1
            fi
          }
          check AWS_ACCESS_KEY_ID      "Secret AWS_ACCESS_KEY_ID vazio (Settings -> Secrets and variables -> Actions -> Secrets)."
          check AWS_SECRET_ACCESS_KEY  "Secret AWS_SECRET_ACCESS_KEY vazio (Settings -> Secrets and variables -> Actions -> Secrets)."
          check EC2_HOST               "Variable AWS_EC2_HOST_HOMOLOG vazia (Settings -> Secrets and variables -> Actions -> Variables). Rode ./scripts/aws/bootstrap-aws.sh --env homolog."
          check SSH_PRIVATE_KEY        "Secret AWS_SSH_KEY_HOMOLOG vazio. Crie como secret de REPOSITÓRIO (Settings -> Secrets and variables -> Actions -> Secrets, conteúdo da .pem do bootstrap) ou no Environment 'homolog'."
          check DB_PASSWORD            "Secret DB_PASSWORD_HOMOLOG vazio. Crie como secret de REPOSITÓRIO ou no Environment 'homolog'."
          check KEYCLOAK_ADMIN_CLIENT_SECRET "Secret KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG vazio. Crie como secret de REPOSITÓRIO ou no Environment 'homolog' (use exatamente o segredo do client raiji-admin)."
          exit "$missing"

  deploy-homolog:
    name: homolog
    needs: preflight
    uses: ./.github/workflows/deploy.yml
    with:
      environment: homolog
      ec2-host: ${{ vars.AWS_EC2_HOST_HOMOLOG }}
    secrets:
      AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
      AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
      SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_HOMOLOG }}
      DB_PASSWORD: ${{ secrets.DB_PASSWORD_HOMOLOG }}
      KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG }}
      SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME_HOMOLOG }}
      SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD_HOMOLOG }}
      # Opcionais: funcionalidades da aplicação (vazio = desligado)
      INFO_SIMPLES_KEY: ${{ secrets.INFO_SIMPLES_KEY }}
      INFOSIMPLES_GOVBR_CPF: ${{ secrets.INFOSIMPLES_GOVBR_CPF }}
      INFOSIMPLES_GOVBR_SENHA: ${{ secrets.INFOSIMPLES_GOVBR_SENHA }}
      RESEND_API_KEY: ${{ secrets.RESEND_API_KEY }}
      RESEND_FROM: ${{ secrets.RESEND_FROM }}
      STRIPE_API_KEY: ${{ secrets.STRIPE_API_KEY }}
      STRIPE_WEBHOOK_SECRET: ${{ secrets.STRIPE_WEBHOOK_SECRET }}
      RAIJI_CORS_ALLOWED_ORIGINS: ${{ secrets.RAIJI_CORS_ALLOWED_ORIGINS }}
      DRIVER_DOCS_BUCKET: ${{ secrets.DRIVER_DOCS_BUCKET }}
      RAIJI_PLATFORM_FEE_PERCENT: ${{ secrets.RAIJI_PLATFORM_FEE_PERCENT }}
      RAIJI_TIME_ZONE: ${{ secrets.RAIJI_TIME_ZONE }}
CD_SETUP_EOF_03_ba847826

  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/rollback.yml" <<'CD_SETUP_EOF_04_7b8db269'
# =============================================================================
# Rollback MANUAL — Actions -> "Rollback (manual)" -> Run workflow -> ambiente.
# Faz: retag <env>-previous -> <env>-current no ECR + redeploy na EC2 + smoke.
# Região/portas lidas de infra/aws/aws-config.env (fonte única).
# Se NÃO existir versão anterior: para o app (não serve versão quebrada) e
# falha com mensagem clara.
# =============================================================================
name: Rollback (manual)

on:
  workflow_dispatch:
    inputs:
      environment:
        description: "Ambiente para rollback"
        required: true
        type: choice
        options: [dev, homolog]

permissions:
  contents: read

jobs:
  rollback:
    name: Rollback ${{ inputs.environment }}
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}
    concurrency:
      group: deploy-${{ inputs.environment }}
      cancel-in-progress: false
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Ler configuração AWS (infra/aws/aws-config.env)
        env:
          AMBIENTE: ${{ inputs.environment }}
        run: |
          set -a; . infra/aws/aws-config.env; set +a
          SUFFIX="$(echo "$AMBIENTE" | tr '[:lower:]' '[:upper:]')"
          bp_var="BASE_PORT_$SUFFIX"
          BASE_PORT_VALUE="${!bp_var:?BASE_PORT_$SUFFIX não definido em infra/aws/aws-config.env}"
          {
            echo "ENVIRONMENT=$AMBIENTE"
            echo "AWS_REGION_NAME=${AWS_REGION:-us-east-1}"
            echo "BASE_PORT=$BASE_PORT_VALUE"
          } >> "$GITHUB_ENV"

      - name: Definir host do ambiente
        env:
          AMBIENTE: ${{ inputs.environment }}
        run: |
          if [[ "$AMBIENTE" == "dev" ]]; then
            HOST="${{ vars.AWS_EC2_HOST_DEV }}"
          else
            HOST="${{ vars.AWS_EC2_HOST_HOMOLOG }}"
          fi
          [[ -n "$HOST" ]] || { echo "::error::variável AWS_EC2_HOST_$AMBIENTE não configurada"; exit 1; }
          {
            echo "EC2_HOST=$HOST"
            echo "BASE_URL=http://$HOST:$BASE_PORT"
          } >> "$GITHUB_ENV"

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION_NAME }}

      - name: Rollback (retag da imagem anterior + redeploy + smoke)
        env:
          EC2_USER: ubuntu
          # NÃO usar 'a && b || c': se o segredo do ambiente estiver vazio, o
          # formato abaixo devolve vazio (o rollback.sh falha com mensagem
          # clara, ou o smoke usa o padrão teste/teste123) em vez de vazear
          # silenciosamente a chave/credencial do OUTRO ambiente.
          SSH_PRIVATE_KEY: ${{ (inputs.environment == 'dev' && secrets.AWS_SSH_KEY_DEV) || (inputs.environment != 'dev' && secrets.AWS_SSH_KEY_HOMOLOG) }}
          SMOKE_USERNAME: ${{ (inputs.environment == 'dev' && secrets.SMOKE_USERNAME_DEV) || (inputs.environment != 'dev' && secrets.SMOKE_USERNAME_HOMOLOG) }}
          SMOKE_PASSWORD: ${{ (inputs.environment == 'dev' && secrets.SMOKE_PASSWORD_DEV) || (inputs.environment != 'dev' && secrets.SMOKE_PASSWORD_HOMOLOG) }}
        run: ./scripts/aws/rollback.sh
CD_SETUP_EOF_04_7b8db269

  mkdir -p "$dst/.github/workflows"
  cat > "$dst/.github/workflows/guardian.yml" <<'CD_SETUP_EOF_05_9638559c'
# =============================================================================
# Guardian — smoke contínuo pós-deploy ("erros inesperados fora do deploy").
# A cada execução (cron */10 + manual), para CADA ambiente em paralelo:
#   1) smoke completo (4 checagens em paralelo)
#   2) falhou? reconfirma após GUARDIAN_RECHECK_DELAY (evita falso positivo)
#   3) falha confirmada + existe <env>-previous -> ROLLBACK AUTOMÁTICO
#   4) sem histórico ou rollback falhou -> abre ISSUE de alerta no repositório
# Cada ambiente usa o MESMO concurrency group do deploy (deploy-dev /
# deploy-homolog): o Guardian nunca concorre com um deploy em andamento.
# =============================================================================
name: Guardian (smoke contínuo + rollback automático)

on:
  schedule:
    - cron: "*/10 * * * *"
  workflow_dispatch:
    inputs:
      environment:
        description: "Ambiente a inspecionar"
        required: false
        default: "all"
        type: choice
        options: [all, dev, homolog]

permissions:
  contents: read
  issues: write

jobs:
  guard-dev:
    name: Guardian · dev
    if: >-
      (github.event_name == 'schedule' || inputs.environment == 'all' || inputs.environment == 'dev')
      && vars.AWS_EC2_HOST_DEV != ''
    runs-on: ubuntu-latest
    environment: dev
    concurrency:
      group: deploy-dev
      cancel-in-progress: false
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Ler configuração AWS (infra/aws/aws-config.env)
        run: |
          set -a; . infra/aws/aws-config.env; set +a
          {
            echo "ENVIRONMENT=dev"
            echo "AWS_REGION_NAME=${AWS_REGION:-us-east-1}"
            echo "BASE_PORT=${BASE_PORT_DEV}"
            echo "BASE_URL=http://${{ vars.AWS_EC2_HOST_DEV }}:${BASE_PORT_DEV}"
          } >> "$GITHUB_ENV"

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION_NAME }}

      - name: Smoke contínuo + rollback automático (guardian.sh)
        env:
          EC2_HOST: ${{ vars.AWS_EC2_HOST_DEV }}
          EC2_USER: ubuntu
          SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_DEV }}
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME_DEV || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD_DEV || 'teste123' }}
        run: ./scripts/aws/guardian.sh

      - name: Alerta — Issue se irrecuperável
        if: failure()
        uses: actions/github-script@v7
        env:
          AMBIENTE: dev
        with:
          script: |
            const ambiente = process.env.AMBIENTE;
            const title = "[Guardian] Ambiente " + ambiente + " indisponível";
            const q = "repo:" + context.repo.owner + "/" + context.repo.repo +
                      " is:issue is:open in:title " + JSON.stringify(title);
            const found = await github.rest.search.issuesAndPullRequests({ q });
            if (found.data.total_count === 0) {
              await github.rest.issues.create({
                owner: context.repo.owner,
                repo: context.repo.repo,
                title: title,
                body: [
                  "Os smoke tests periódicos do **Guardian** falharam para o ambiente `" + ambiente + "`",
                  "e a recuperação automática (rollback) não teve sucesso — ou não existe versão anterior.",
                  "",
                  "- URL testada: " + (process.env.BASE_URL || "n/d"),
                  "- Execução: " + context.serverUrl + "/" + context.repo.owner + "/" + context.repo.repo + "/actions/runs/" + context.runId,
                  "",
                  "Verifique os logs, corrija e faça novo deploy (ou rode o workflow *Rollback (manual)* se houver versão anterior)."
                ].join("\n")
              });
              core.info("Issue de alerta criada.");
            } else {
              core.info("Já existe Issue aberta para este ambiente — nada a criar.");
            }

  guard-homolog:
    name: Guardian · homolog
    if: >-
      (github.event_name == 'schedule' || inputs.environment == 'all' || inputs.environment == 'homolog')
      && vars.AWS_EC2_HOST_HOMOLOG != ''
    runs-on: ubuntu-latest
    environment: homolog
    concurrency:
      group: deploy-homolog
      cancel-in-progress: false
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Ler configuração AWS (infra/aws/aws-config.env)
        run: |
          set -a; . infra/aws/aws-config.env; set +a
          {
            echo "ENVIRONMENT=homolog"
            echo "AWS_REGION_NAME=${AWS_REGION:-us-east-1}"
            echo "BASE_PORT=${BASE_PORT_HOMOLOG}"
            echo "BASE_URL=http://${{ vars.AWS_EC2_HOST_HOMOLOG }}:${BASE_PORT_HOMOLOG}"
          } >> "$GITHUB_ENV"

      - name: Credenciais AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION_NAME }}

      - name: Smoke contínuo + rollback automático (guardian.sh)
        env:
          EC2_HOST: ${{ vars.AWS_EC2_HOST_HOMOLOG }}
          EC2_USER: ubuntu
          SSH_PRIVATE_KEY: ${{ secrets.AWS_SSH_KEY_HOMOLOG }}
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME_HOMOLOG || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD_HOMOLOG || 'teste123' }}
        run: ./scripts/aws/guardian.sh

      - name: Alerta — Issue se irrecuperável
        if: failure()
        uses: actions/github-script@v7
        env:
          AMBIENTE: homolog
        with:
          script: |
            const ambiente = process.env.AMBIENTE;
            const title = "[Guardian] Ambiente " + ambiente + " indisponível";
            const q = "repo:" + context.repo.owner + "/" + context.repo.repo +
                      " is:issue is:open in:title " + JSON.stringify(title);
            const found = await github.rest.search.issuesAndPullRequests({ q });
            if (found.data.total_count === 0) {
              await github.rest.issues.create({
                owner: context.repo.owner,
                repo: context.repo.repo,
                title: title,
                body: [
                  "Os smoke tests periódicos do **Guardian** falharam para o ambiente `" + ambiente + "`",
                  "e a recuperação automática (rollback) não teve sucesso — ou não existe versão anterior.",
                  "",
                  "- URL testada: " + (process.env.BASE_URL || "n/d"),
                  "- Execução: " + context.serverUrl + "/" + context.repo.owner + "/" + context.repo.repo + "/actions/runs/" + context.runId,
                  "",
                  "Verifique os logs, corrija e faça novo deploy (ou rode o workflow *Rollback (manual)* se houver versão anterior)."
                ].join("\n")
              });
              core.info("Issue de alerta criada.");
            } else {
              core.info("Já existe Issue aberta para este ambiente — nada a criar.");
            }
CD_SETUP_EOF_05_9638559c

  mkdir -p "$dst/scripts"
  cat > "$dst/scripts/smoke-test.sh" <<'CD_SETUP_EOF_06_d7403c6c'
#!/usr/bin/env bash
# =============================================================================
# smoke-test.sh — S1-03 · Smoke tests pós-deploy
#
# Checagens (exatamente as da issue):
#   health -> GET  {BASE_URL}/actuator/health            == 200 e {"status":"UP"}
#   realm  -> GET  {KEYCLOAK_URL}/realms/raiji           == 200
#   token  -> POST {KEYCLOAK_URL}/realms/raiji/protocol/openid-connect/token == 200
#   me     -> GET  {BASE_URL}/api/me com Bearer token    == 200
#
# Uso:
#   ./scripts/smoke-test.sh                       # todas, em PARALELO (padrão)
#   ./scripts/smoke-test.sh --serial              # todas, em série
#   ./scripts/smoke-test.sh --check health        # uma checagem (usado na matriz do CD)
#
# Variáveis de ambiente:
#   BASE_URL (default http://localhost:8000)  KEYCLOAK_URL (default = BASE_URL)
#   REALM (raiji)  CLIENT_ID (raiji-api)  SMOKE_USERNAME (teste)  SMOKE_PASSWORD (teste123)
#   HTTP_TIMEOUT (10)  RETRIES (3)  RETRY_DELAY (5)  SMOKE_FORCE_PYTHON (0|1)
#
# HTTP: usa curl se existir; senão, python3 (urllib) como fallback.
# Saída: 0 = todas passaram | 1 = alguma falhou | 2 = erro de uso
# =============================================================================
set -u

CHECKS_ALL=(health realm token me)
CHECK=""
MODE="parallel"
BASE_URL="${BASE_URL:-http://localhost:8000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-}"
REALM="${REALM:-raiji}"
CLIENT_ID="${CLIENT_ID:-raiji-api}"
SMOKE_USERNAME="${SMOKE_USERNAME:-teste}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-10}"
RETRIES="${RETRIES:-3}"
RETRY_DELAY="${RETRY_DELAY:-5}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)        CHECK="${2:-}"; shift 2 ;;
    --all)          CHECK=""; shift ;;
    --parallel)     MODE="parallel"; shift ;;
    --serial)       MODE="serial"; shift ;;
    --base)         BASE_URL="${2:-}"; shift 2 ;;
    --keycloak)     KEYCLOAK_URL="${2:-}"; shift 2 ;;
    --realm)        REALM="${2:-}"; shift 2 ;;
    --client-id)    CLIENT_ID="${2:-}"; shift 2 ;;
    --user)         SMOKE_USERNAME="${2:-}"; shift 2 ;;
    --password)     SMOKE_PASSWORD="${2:-}"; shift 2 ;;
    --retries)      RETRIES="${2:-}"; shift 2 ;;
    --retry-delay)  RETRY_DELAY="${2:-}"; shift 2 ;;
    --timeout)      HTTP_TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help)      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

# KEYCLOAK_URL padrão = BASE_URL (o Kong roteia /realms) — resolvido DEPOIS do
# parse para respeitar --base.
[[ -n "$KEYCLOAK_URL" ]] || KEYCLOAK_URL="$BASE_URL"

# Cores apenas em terminal (logs de CI saem limpos e "grepáveis").
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
else
  RED=""; GRN=""; YLW=""; NC=""
fi
ok()   { printf '%s[PASS]%s %s\n' "$GRN" "$NC" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$NC" "$*"; }
info() { printf '%s[..]%s %s\n'   "$YLW" "$NC" "$*"; }

TMPDIR_SMOKE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_SMOKE"' EXIT

# -----------------------------------------------------------------------------
# Camada HTTP — curl preferencial, python3 como fallback.
# Preenche: HTTP_CODE (numérico; 0 = erro de conexão) e HTTP_BODY (arquivo).
# -----------------------------------------------------------------------------
_urlencode() {
  local s="$1" out="" i c
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

_http_python() { # method url outfile content-type body auth
  SMOKE_METHOD="$1" SMOKE_URL="$2" SMOKE_CT="${4:-}" SMOKE_BODY="${5:-}" \
  SMOKE_AUTH="${6:-}" SMOKE_TIMEOUT="$HTTP_TIMEOUT" \
  python3 - "$3" <<'PYEOF'
import os, sys, urllib.request, urllib.error
req = urllib.request.Request(os.environ["SMOKE_URL"], method=os.environ["SMOKE_METHOD"])
body = os.environ.get("SMOKE_BODY") or ""
data = body.encode() if body else None
if data is not None:
    req.add_header("Content-Type", os.environ.get("SMOKE_CT") or "application/x-www-form-urlencoded")
auth = os.environ.get("SMOKE_AUTH") or ""
if auth:
    req.add_header("Authorization", auth)
try:
    resp = urllib.request.urlopen(req, data=data, timeout=int(float(os.environ.get("SMOKE_TIMEOUT", "10"))))
    code, payload = resp.status, resp.read()
except urllib.error.HTTPError as e:
    code, payload = e.code, e.read() if e.fp else b""
except Exception as e:  # conexão recusada, timeout, DNS...
    code, payload = 0, str(e).encode()
with open(sys.argv[1], "wb") as f:
    f.write(payload)
print(code)
PYEOF
}

_http_curl() { # method url outfile content-type body auth
  local method="$1" url="$2" out="$3" ct="${4:-}" body="${5:-}" auth="${6:-}"
  local args=(-sS -o "$out" -w '%{http_code}' --max-time "$HTTP_TIMEOUT" -X "$method")
  [[ -n "$ct"   ]] && args+=(-H "Content-Type: $ct")
  [[ -n "$auth" ]] && args+=(-H "Authorization: $auth")
  [[ -n "$body" ]] && args+=(--data "$body")
  local code
  code="$(curl "${args[@]}" "$url" 2>/dev/null || true)"
  [[ "$code" == "000" || -z "$code" ]] && code=0
  printf '%s' "$code"
}

HTTP_CODE=0
HTTP_BODY=""
http_request() { # method url [content-type] [body] [auth]
  HTTP_BODY="$TMPDIR_SMOKE/body.$$.$RANDOM"
  if [[ "${SMOKE_FORCE_PYTHON:-0}" == "1" ]] || ! command -v curl >/dev/null 2>&1; then
    HTTP_CODE="$(_http_python "$1" "$2" "$HTTP_BODY" "${3:-}" "${4:-}" "${5:-}")"
  else
    HTTP_CODE="$(_http_curl "$1" "$2" "$HTTP_BODY" "${3:-}" "${4:-}" "${5:-}")"
  fi
  [[ -z "$HTTP_CODE" ]] && HTTP_CODE=0
}

# -----------------------------------------------------------------------------
# Checagens
# -----------------------------------------------------------------------------
obter_token() { # -> grava token em TOKEN; retorna 0/1
  TOKEN=""
  local body
  body="grant_type=password&client_id=$(_urlencode "$CLIENT_ID")&username=$(_urlencode "$SMOKE_USERNAME")&password=$(_urlencode "$SMOKE_PASSWORD")"
  http_request POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    "application/x-www-form-urlencoded" "$body" ""
  [[ "$HTTP_CODE" == "200" ]] || return 1
  TOKEN="$(sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$HTTP_BODY" | head -n1)"
  [[ -n "$TOKEN" ]]
}

check_health() {
  http_request GET "$BASE_URL/actuator/health"
  if [[ "$HTTP_CODE" == "200" ]] && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$HTTP_BODY"; then
    ok "health — GET /actuator/health -> 200 {\"status\":\"UP\"}"
    return 0
  fi
  fail "health — GET /actuator/health -> HTTP $HTTP_CODE (esperado 200 + status UP); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

check_realm() {
  http_request GET "$KEYCLOAK_URL/realms/$REALM"
  if [[ "$HTTP_CODE" == "200" ]]; then
    ok "realm — GET /realms/$REALM -> 200"
    return 0
  fi
  fail "realm — GET /realms/$REALM -> HTTP $HTTP_CODE (esperado 200)"
  return 1
}

check_token() {
  if obter_token; then
    ok "token — POST /realms/$REALM/protocol/openid-connect/token ($SMOKE_USERNAME) -> 200 + access_token"
    return 0
  fi
  fail "token — POST /realms/$REALM/protocol/openid-connect/token -> HTTP $HTTP_CODE (esperado 200); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

check_me() {
  if ! obter_token; then
    fail "me — não foi possível obter token (HTTP $HTTP_CODE)"
    return 1
  fi
  http_request GET "$BASE_URL/api/me" "" "" "Bearer $TOKEN"
  if [[ "$HTTP_CODE" == "200" ]]; then
    ok "me — GET /api/me com Bearer token -> 200"
    return 0
  fi
  fail "me — GET /api/me -> HTTP $HTTP_CODE (esperado 200); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

run_with_retries() { # check_name
  local name="$1" attempt
  for (( attempt = 1; attempt <= RETRIES; attempt++ )); do
    if "check_$name"; then return 0; fi
    [[ "$attempt" -lt "$RETRIES" ]] && sleep "$RETRY_DELAY"
  done
  return 1
}

# -----------------------------------------------------------------------------
# Execução
# -----------------------------------------------------------------------------
run_one() { # name -> grava rc em arquivo (modo paralelo)
  local name="$1"
  if run_with_retries "$name"; then
    echo 0 > "$TMPDIR_SMOKE/$name.rc"
  else
    echo 1 > "$TMPDIR_SMOKE/$name.rc"
  fi
}

main() {
  local checks=()
  if [[ -n "$CHECK" ]]; then
    case "$CHECK" in
      health|realm|token|me) checks=("$CHECK") ;;
      all|"") checks=("${CHECKS_ALL[@]}") ;;
      *) echo "Check inválido: $CHECK (use health|realm|token|me|all)" >&2; exit 2 ;;
    esac
  else
    checks=("${CHECKS_ALL[@]}")
  fi

  info "Smoke tests em $BASE_URL (modo: $MODE, checagens: ${checks[*]})"

  if [[ "$MODE" == "parallel" && ${#checks[@]} -gt 1 ]]; then
    # Paralelo: cada checagem em subshell independente (o check 'me' obtém o
    # próprio token, logo não há dependência entre os jobs).
    local name pids=()
    for name in "${checks[@]}"; do
      run_one "$name" &
      pids+=($!)
    done
    for pid in "${pids[@]}"; do wait "$pid" || true; done
  else
    local name
    for name in "${checks[@]}"; do
      run_one "$name"
    done
  fi

  local failed=0 name rc
  echo "----------------------------------------"
  for name in "${checks[@]}"; do
    rc="$(cat "$TMPDIR_SMOKE/$name.rc" 2>/dev/null || echo 1)"
    [[ "$rc" != "0" ]] && failed=$((failed + 1))
  done

  if [[ "$failed" -eq 0 ]]; then
    ok "SMOKE OK — ${#checks[@]}/${#checks[@]} checagens passaram"
    exit 0
  fi
  fail "SMOKE FALHOU — $failed/${#checks[@]} checagens falharam"
  exit 1
}

main
CD_SETUP_EOF_06_d7403c6c

  mkdir -p "$dst/scripts"
  cat > "$dst/scripts/setup-cicd.sh" <<'CD_SETUP_EOF_07_76805935'
#!/usr/bin/env bash
# =============================================================================
# setup-cicd.sh — NÃO gera mais arquivos (modo somente verificação).
#
# Histórico: este script era um gerador "tudo-em-um" que sobrescrevia os
# workflows e os scripts de deploy. Ele ficou desatualizado e, ao rodar,
# APAGAVA correções mais novas (ex.: `build: !reset`, `PUBLIC_BASE_URL`,
# sincronização do RabbitMQ). Para evitar regressão, ele agora apenas:
#
#   1. verifica se todos os arquivos de CI/CD existem;
#   2. imprime os próximos passos.
#
# É idempotente por construção: nunca escreve em arquivos versionados.
# Uso: ./scripts/setup-cicd.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REQUIRED=(
  .github/workflows/ci.yml
  .github/workflows/deploy.yml
  .github/workflows/deploy-dev.yml
  .github/workflows/deploy-homolog.yml
  .github/workflows/rollback.yml
  .github/workflows/guardian.yml
  scripts/smoke-test.sh
  scripts/aws/deploy.sh
  scripts/aws/deploy-remote.sh
  scripts/aws/rollback.sh
  scripts/aws/ecr-retag.sh
  scripts/aws/bootstrap-aws.sh
  scripts/aws/guardian.sh
  docker-compose.aws.yml
  docs/DEPLOY.md
  docs/CD-CHECKLIST.md
  infra/aws/aws-config.env
)

missing=0
for f in "${REQUIRED[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "[setup-cicd] FALTANDO: $f" >&2
    missing=1
  fi
done

if [[ "$missing" -ne 0 ]]; then
  echo "[setup-cicd] ERRO: arquivos de CI/CD ausentes (veja acima)." >&2
  exit 1
fi

cat <<'FIM'

[setup-cicd] OK — todos os arquivos de CI/CD estão presentes.
[setup-cicd] Este script não gera nem sobrescreve nada (somente verificação).

Próximos passos para o CD funcionar:
  1) ./scripts/aws/bootstrap-aws.sh --env dev
     ./scripts/aws/bootstrap-aws.sh --env homolog
  2) Configure os secrets/variables no GitHub (veja docs/CD-CHECKLIST.md)
  3) ./scripts/aws/check-github-config.sh   (requer gh autenticado)
  4) git push origin develop                (dispara o Deploy DEV)
  5) git push origin develop:main           (sincroniza main -> HOMOLOG/Guardian)

FIM
CD_SETUP_EOF_07_76805935

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/bootstrap-aws.sh" <<'CD_SETUP_EOF_08_d2e71019'
#!/usr/bin/env bash
# =============================================================================
# bootstrap-aws.sh — provisiona (e MANTÉM) a infra AWS de cada ambiente.
#
# Idempotente: rode de novo sempre que quiser. Lê a configuração de
# infra/aws/aws-config.env (tipo de máquina, região, portas, AMI). Flags de
# CLI têm precedência sobre o arquivo.
#
#   ./scripts/aws/bootstrap-aws.sh --env dev
#   ./scripts/aws/bootstrap-aws.sh --env homolog
#   ./scripts/aws/bootstrap-aws.sh --env dev --instance-type t3.medium   # resize
#
# MUDOU O TIPO DE MÁQUINA no aws-config.env? Rerode o bootstrap: ele detecta
# a diferença, para a instância, aplica o novo tipo e religa (resize automático).
#
# Cria (uma vez): ECR `raiji-app`, security group, key pair, IAM role (pull
# ECR) e instância EC2 Ubuntu 24.04 com Docker+Compose via user-data.
# No final, imprime EXATAMENTE o que configurar no GitHub (secrets/variables).
#
# Requer: aws cli v2 configurado com permissões de EC2/IAM/ECR.
# =============================================================================
set -euo pipefail

CONFIG_FILE="infra/aws/aws-config.env"
CLI_ENV="" CLI_REGION="" CLI_INSTANCE_TYPE="" CLI_BASE_PORT="" CLI_KC_PORT="" CLI_AMI=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) CLI_ENV="$2"; shift 2 ;;
    --region) CLI_REGION="$2"; shift 2 ;;
    --instance-type) CLI_INSTANCE_TYPE="$2"; shift 2 ;;
    --base-port) CLI_BASE_PORT="$2"; shift 2 ;;
    --kc-port) CLI_KC_PORT="$2"; shift 2 ;;
    --ami) CLI_AMI="$2"; shift 2 ;;
    --config) CONFIG_FILE="$2"; shift 2 ;;
    *) echo "opção inválida: $1" >&2; exit 2 ;;
  esac
done

ENVIRONMENT="$CLI_ENV"
[[ "$ENVIRONMENT" =~ ^(dev|homolog)$ ]] || { echo "--env deve ser dev|homolog" >&2; exit 2; }

# --- carrega o arquivo de configuração (fonte única) ---------------------------
if [[ -f "$CONFIG_FILE" ]]; then
  set -a; . "$CONFIG_FILE"; set +a
  echo "[bootstrap] configuração carregada de $CONFIG_FILE"
else
  echo "[bootstrap] AVISO: $CONFIG_FILE não encontrado — usando padrões do script."
fi
SUFFIX="$(echo "$ENVIRONMENT" | tr '[:lower:]' '[:upper:]')"
it_var="INSTANCE_TYPE_$SUFFIX"; bp_var="BASE_PORT_$SUFFIX"; kp_var="KC_PORT_$SUFFIX"; ami_var="AMI_$SUFFIX"

REGION="${CLI_REGION:-${AWS_REGION:-us-east-1}}"
INSTANCE_TYPE="${CLI_INSTANCE_TYPE:-${!it_var:-t3.small}}"
BASE_PORT="${CLI_BASE_PORT:-${!bp_var:-8000}}"
KC_PORT="${CLI_KC_PORT:-${!kp_var:-$((BASE_PORT + 180))}}"
AMI="${CLI_AMI:-${!ami_var:-}}"

KEY_NAME="raiji-${ENVIRONMENT}-key"
REPO="raiji-app"
ROLE="raiji-ec2-ecr-${ENVIRONMENT}"
SG_NAME="raiji-${ENVIRONMENT}-sg"
ACTIONS_USER="raiji-actions"
log() { echo "[bootstrap:$ENVIRONMENT] $*"; }

command -v aws >/dev/null || { echo "aws cli não encontrado" >&2; exit 1; }
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" \
  || { echo "aws sts falhou — confira suas credenciais" >&2; exit 1; }
# Bucket do S3 para a foto da CNH (nome único por conta+ambiente).
DRIVER_DOCS_BUCKET="raiji-driver-docs-${ACCOUNT_ID}-${ENVIRONMENT}"
log "configuração: conta=$ACCOUNT_ID região=$REGION tipo=$INSTANCE_TYPE portas=$BASE_PORT/$KC_PORT"
log "bucket CNH: $DRIVER_DOCS_BUCKET"

# --- S3: bucket privado para a foto da CNH (idempotente) ------------------------
if aws s3api head-bucket --bucket "$DRIVER_DOCS_BUCKET" >/dev/null 2>&1; then
  log "bucket S3 $DRIVER_DOCS_BUCKET já existe."
else
  log "criando bucket S3 $DRIVER_DOCS_BUCKET ..."
  if [[ "$REGION" == "us-east-1" ]]; then
    aws s3api create-bucket --bucket "$DRIVER_DOCS_BUCKET" --region "$REGION" >/dev/null
  else
    aws s3api create-bucket --bucket "$DRIVER_DOCS_BUCKET" --region "$REGION" \
      --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  fi
fi
# Sempre reaplica as proteções (barato e idempotente).
aws s3api put-public-access-block --bucket "$DRIVER_DOCS_BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true >/dev/null
aws s3api put-bucket-encryption --bucket "$DRIVER_DOCS_BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' >/dev/null 2>&1 || true

# --- ECR (idempotente) ----------------------------------------------------------
if ! aws ecr describe-repositories --region "$REGION" --repository-names "$REPO" >/dev/null 2>&1; then
  log "criando repositório ECR $REPO ..."
  aws ecr create-repository --region "$REGION" --repository-name "$REPO" \
    --image-scanning-configuration scanOnPush=true >/dev/null
else
  log "ECR $REPO já existe."
fi

# --- IAM role da EC2: pull do ECR + S3 da CNH -----------------------------------
if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  log "criando IAM role $ROLE ..."
  aws iam create-role --role-name "$ROLE" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
else
  log "IAM role $ROLE já existe."
fi
# Reaplica a policy sempre: assim roles antigas ganham S3 sem recriar a instância.
aws iam put-role-policy --role-name "$ROLE" --policy-name ecr-pull --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Action\": [\"ecr:GetAuthorizationToken\"],
    \"Resource\": \"*\"
  }, {
    \"Effect\": \"Allow\",
    \"Action\": [\"ecr:BatchCheckLayerAvailability\",\"ecr:GetDownloadUrlForLayer\",\"ecr:BatchGetImage\"],
    \"Resource\": \"arn:aws:ecr:${REGION}:${ACCOUNT_ID}:repository/${REPO}\"
  }, {
    \"Effect\": \"Allow\",
    \"Action\": [\"s3:ListBucket\"],
    \"Resource\": \"arn:aws:s3:::${DRIVER_DOCS_BUCKET}\"
  }, {
    \"Effect\": \"Allow\",
    \"Action\": [\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],
    \"Resource\": \"arn:aws:s3:::${DRIVER_DOCS_BUCKET}/*\"
  }]
}"
if ! aws iam get-instance-profile --instance-profile-name "$ROLE" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
  sleep 10
fi

# --- IAM user do GitHub Actions: push/retag no ECR + STS ------------------------
if ! aws iam get-user --user-name "$ACTIONS_USER" >/dev/null 2>&1; then
  log "criando IAM user $ACTIONS_USER (usado pelo GitHub Actions) ..."
  aws iam create-user --user-name "$ACTIONS_USER" >/dev/null
else
  log "IAM user $ACTIONS_USER já existe."
fi
aws iam put-user-policy --user-name "$ACTIONS_USER" --policy-name raiji-actions-ecr --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Action\": [\"ecr:GetAuthorizationToken\"],
    \"Resource\": \"*\"
  }, {
    \"Effect\": \"Allow\",
    \"Action\": [
      \"ecr:BatchCheckLayerAvailability\",\"ecr:GetDownloadUrlForLayer\",\"ecr:BatchGetImage\",
      \"ecr:PutImage\",\"ecr:InitiateLayerUpload\",\"ecr:UploadLayerPart\",
      \"ecr:CompleteLayerUpload\",\"ecr:DescribeImages\"
    ],
    \"Resource\": \"arn:aws:ecr:${REGION}:${ACCOUNT_ID}:repository/${REPO}\"
  }, {
    \"Effect\": \"Allow\",
    \"Action\": [\"sts:GetCallerIdentity\"],
    \"Resource\": \"*\"
  }]
}"
ACTIONS_KEY_FILE="$HOME/.ssh/raiji-actions-access-key.json"
if [[ "$(aws iam list-access-keys --user-name "$ACTIONS_USER" --query 'length(AccessKeyMetadata)' --output text)" == "0" ]]; then
  log "criando access key para $ACTIONS_USER -> $ACTIONS_KEY_FILE"
  mkdir -p "$HOME/.ssh"
  (umask 077; aws iam create-access-key --user-name "$ACTIONS_USER" --output json > "$ACTIONS_KEY_FILE")
else
  log "access key de $ACTIONS_USER já existe (não é possível exibir a secret de novo)."
fi

# --- Security group (cria e garante as portas do config) --------------------------
VPC_ID="$(aws ec2 describe-vpcs --region "$REGION" \
  --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)"
SG_ID="$(aws ec2 describe-security-groups --region "$REGION" \
  --filters Name=group-name,Values="$SG_NAME" --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || true)"
if [[ -z "$SG_ID" || "$SG_ID" == "None" ]]; then
  log "criando security group $SG_NAME (22, $BASE_PORT, $KC_PORT) ..."
  SG_ID="$(aws ec2 create-security-group --region "$REGION" --group-name "$SG_NAME" \
    --description "Raiji $ENVIRONMENT" --vpc-id "$VPC_ID" --query GroupId --output text)"
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
    --protocol tcp --port 22 --cidr 0.0.0.0/0 >/dev/null
  log "AVISO: SSH (22) aberto para 0.0.0.0/0 — restrinja o CIDR em produção."
else
  log "security group já existe: $SG_ID"
fi
for P in "$BASE_PORT" "$KC_PORT"; do
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
    --protocol tcp --port "$P" --cidr 0.0.0.0/0 >/dev/null 2>&1 \
    && log "porta $P liberada no security group" \
    || log "porta $P já liberada"
done

# --- Key pair -----------------------------------------------------------------------
KEY_FILE="$HOME/.ssh/raiji-${ENVIRONMENT}-pem"
if ! aws ec2 describe-key-pairs --region "$REGION" --key-names "$KEY_NAME" >/dev/null 2>&1; then
  log "criando key pair $KEY_NAME -> $KEY_FILE"
  mkdir -p "$HOME/.ssh"
  aws ec2 create-key-pair --region "$REGION" --key-name "$KEY_NAME" \
    --query 'KeyMaterial' --output text > "$KEY_FILE"
  chmod 600 "$KEY_FILE"
else
  log "key pair $KEY_NAME já existe (use a .pem salva anteriormente)."
fi

# --- AMI ----------------------------------------------------------------------------
if [[ -z "$AMI" ]]; then
  AMI="$(aws ssm get-parameters --region "$REGION" \
    --names /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    --query 'Parameters[0].Value' --output text)"
fi

USER_DATA='#!/bin/bash
set -euxo pipefail
apt-get update -y
apt-get install -y docker.io docker-compose-v2 awscli curl jq
systemctl enable --now docker
usermod -aG docker ubuntu
mkdir -p /opt/raiji
chown -R ubuntu:ubuntu /opt/raiji'

# --- Instância: cria, ou REDIMENSIONA se o tipo mudou no config --------------------
EXISTING="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:RaijiEnv,Values=$ENVIRONMENT" "Name=instance-state-name,Values=running,stopped,pending" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)"

if [[ -n "$EXISTING" && "$EXISTING" != "None" ]]; then
  CURRENT_TYPE="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].InstanceType' --output text)"
  STATE="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].State.Name' --output text)"
  log "instância existente: $EXISTING ($CURRENT_TYPE, $STATE)"
  ID="$EXISTING"

  if [[ "$CURRENT_TYPE" != "$INSTANCE_TYPE" ]]; then
    log "ALTERANDO tipo de máquina: $CURRENT_TYPE -> $INSTANCE_TYPE ..."
    if [[ "$STATE" == "running" || "$STATE" == "pending" ]]; then
      aws ec2 wait instance-running --region "$REGION" --instance-ids "$EXISTING" 2>/dev/null || true
      aws ec2 stop-instances --region "$REGION" --instance-ids "$EXISTING" >/dev/null
      aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$EXISTING"
    fi
    aws ec2 modify-instance-attribute --region "$REGION" --instance-id "$EXISTING" \
      --instance-type "{\"Value\": \"$INSTANCE_TYPE\"}"
    aws ec2 start-instances --region "$REGION" --instance-ids "$EXISTING" >/dev/null
    aws ec2 wait instance-running --region "$REGION" --instance-ids "$EXISTING"
    log "resize concluído: $INSTANCE_TYPE"
  fi
else
  log "lançando instância $INSTANCE_TYPE (AMI $AMI) ..."
  ID="$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" \
    --instance-type "$INSTANCE_TYPE" --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" --iam-instance-profile Name="$ROLE" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=raiji-$ENVIRONMENT},{Key=RaijiEnv,Value=$ENVIRONMENT}]" \
    --user-data "$USER_DATA" \
    --query 'Instances[0].InstanceId' --output text)"
  log "aguardando a instância $ID ficar running ..."
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$ID"
fi

# --- Elastic IP: o host não muda em stop/start (variável do GitHub estável) ------
EIP_ALLOC="$(aws ec2 describe-addresses --region "$REGION" \
  --filters "Name=tag:RaijiEnv,Values=$ENVIRONMENT" \
  --query 'Addresses[0].AllocationId' --output text 2>/dev/null || true)"
if [[ -z "$EIP_ALLOC" || "$EIP_ALLOC" == "None" ]]; then
  log "alocando Elastic IP para $ENVIRONMENT ..."
  EIP_ALLOC="$(aws ec2 allocate-address --region "$REGION" --domain vpc \
    --query 'AllocationId' --output text)"
  aws ec2 create-tags --region "$REGION" --resources "$EIP_ALLOC" \
    --tags "Key=Name,Value=raiji-$ENVIRONMENT-eip" "Key=RaijiEnv,Value=$ENVIRONMENT"
else
  log "Elastic IP já existe: $EIP_ALLOC"
fi
aws ec2 associate-address --region "$REGION" --instance-id "$ID" \
  --allocation-id "$EIP_ALLOC" >/dev/null 2>&1 \
  && log "Elastic IP associado à instância $ID" \
  || log "Elastic IP já associado (ou associação pendente)"
IP="$(aws ec2 describe-addresses --region "$REGION" --allocation-ids "$EIP_ALLOC" \
  --query 'Addresses[0].PublicIp' --output text)"

SUFIXO="$SUFFIX"
cat <<FIM

============================================================
 Bootstrap $ENVIRONMENT concluído!
============================================================
 Config em uso: $CONFIG_FILE (edite-o para mudar tipo de
 máquina/região/portas e rerode este script).

 URL do ambiente: http://$IP:$BASE_PORT
 Bucket S3 da CNH: $DRIVER_DOCS_BUCKET

 Configure no GitHub (Settings -> Secrets and variables -> Actions):

 [Variables] (aba Variables)
   AWS_EC2_HOST_$SUFIXO = $IP

 [Secrets] (aba Secrets — REPOSITÓRIO; é o local recomendado)
   AWS_ACCESS_KEY_ID               = <AccessKeyId de $ACTIONS_KEY_FILE>
   AWS_SECRET_ACCESS_KEY           = <SecretAccessKey de $ACTIONS_KEY_FILE>
   AWS_SSH_KEY_$SUFIXO             = conteúdo de $KEY_FILE
   DB_PASSWORD_$SUFIXO             = <senha forte do banco>
   KEYCLOAK_ADMIN_CLIENT_SECRET_$SUFIXO = <segredo do client raiji-admin>
   SMOKE_USERNAME_$SUFIXO          = teste            (opcional)
   SMOKE_PASSWORD_$SUFIXO          = <senha do usuário de smoke> (opcional)

 [Environment '$ENVIRONMENT' — OPCIONAL, só para aprovação]
   Settings -> Environments -> New environment '$ENVIRONMENT'.
   No homolog, ative "Required reviewers" para aprovação manual.
   Secrets de Environment TAMBÉM funcionam (os workflows declaram
   environment e resolvem os dois escopos) — mas não duplique valores
   diferentes no repositório e no environment.

 [Secrets opcionais] (repositório — funcionalidades da aplicação)
   INFO_SIMPLES_KEY, INFOSIMPLES_GOVBR_CPF, INFOSIMPLES_GOVBR_SENHA,
   RESEND_API_KEY, RESEND_FROM, STRIPE_API_KEY, STRIPE_WEBHOOK_SECRET,
   RAIJI_CORS_ALLOWED_ORIGINS

 Credenciais do IAM user do Actions (NÃO commitar):
   $ACTIONS_KEY_FILE

 A instância nova precisa de ~2-5 min para instalar Docker (user-data).
 Teste: ssh -i $KEY_FILE ubuntu@$IP 'docker --version'
============================================================
FIM
CD_SETUP_EOF_08_d2e71019

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/deploy.sh" <<'CD_SETUP_EOF_09_523449a8'
#!/usr/bin/env bash
# =============================================================================
# deploy.sh — publica um ambiente na EC2 (roda no GitHub Actions runner).
#
# Requer (env):
#   ENVIRONMENT (dev|homolog), EC2_HOST, EC2_USER (ubuntu), SSH_PRIVATE_KEY,
#   AWS_REGION_NAME, BASE_PORT; segredos: DB_PASSWORD, KEYCLOAK_ADMIN_CLIENT_SECRET
# Requer (ferramentas): aws cli (credenciais já configuradas), ssh, tar.
#
# Passos:
#   1. descobre o registry ECR da conta
#   2. sincroniza os arquivos de infra para /opt/raiji/<env> na EC2
#   3. grava o .env remoto (variáveis do provedor — nunca commitado)
#   4. executa deploy-remote.sh (pull da imagem, up -d, health-gate, self-rollback)
# =============================================================================
set -euo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório (dev|homolog)}"
: "${EC2_HOST:?EC2_HOST obrigatório (defina a variável AWS_EC2_HOST_${ENVIRONMENT^^} no GitHub)}"
: "${EC2_USER:=ubuntu}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY obrigatório}"
: "${AWS_REGION_NAME:=us-east-1}"
: "${BASE_PORT:?BASE_PORT obrigatório (dev=8000, homolog=9000)}"
: "${DB_PASSWORD:?DB_PASSWORD obrigatório}"
: "${KEYCLOAK_ADMIN_CLIENT_SECRET:?KEYCLOAK_ADMIN_CLIENT_SECRET obrigatório}"

REMOTE_DIR="/opt/raiji/$ENVIRONMENT"
KC_PORT="${KC_PORT:-$((BASE_PORT + 180))}"

log() { echo "[deploy:$ENVIRONMENT] $*"; }
die() { echo "[deploy:$ENVIRONMENT] ERRO: $*" >&2; exit 1; }

# --- 1) SSH key temporária ---------------------------------------------------
KEY_FILE="$(mktemp)"
umask 077; printf '%s\n' "$SSH_PRIVATE_KEY" > "$KEY_FILE"
cleanup() { rm -f "$KEY_FILE"; }
trap cleanup EXIT

SSH_OPTS=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)
SSH_TARGET="$EC2_USER@$EC2_HOST"

# --- 2) Registry ECR ----------------------------------------------------------
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" \
  || die "aws sts falhou — confira AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY"
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION_NAME}.amazonaws.com"
IMAGE_TAG="${IMAGE_TAG:-${ENVIRONMENT}-current}"
# Bucket do S3 para a foto da CNH. O bootstrap cria exatamente este nome.
DRIVER_DOCS_BUCKET="${DRIVER_DOCS_BUCKET:-raiji-driver-docs-${ACCOUNT_ID}-${ENVIRONMENT}}"
log "ECR registry: $ECR_REGISTRY | tag: $IMAGE_TAG | bucket CNH: $DRIVER_DOCS_BUCKET"

# Aspas simples seguras para o formato .env do Compose (aceita espaços e $).
sq() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# --- 3) Sincronizar arquivos de infra -----------------------------------------
log "sincronizando arquivos para $SSH_TARGET:$REMOTE_DIR ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p '$REMOTE_DIR/scripts/aws'"
tar czf - --exclude='.env' --exclude='target' \
  docker-compose.yml docker-compose.aws.yml \
  kong keycloak postgres rabbitmq \
  scripts/aws/deploy-remote.sh scripts/smoke-test.sh \
  | ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "tar xzf - -C '$REMOTE_DIR'"

# --- 4) Gravar .env remoto (stdin — segredos não aparecem em linha de comando)
log "gravando .env remoto (umask 077) ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "umask 077; cat > '$REMOTE_DIR/.env'" <<ENV_EOF
COMPOSE_PROJECT_NAME=raiji-$ENVIRONMENT
ENVIRONMENT=$ENVIRONMENT
ECR_REGISTRY=$ECR_REGISTRY
ECR_REPOSITORY=raiji-app
IMAGE_TAG=$IMAGE_TAG
BASE_PORT=$BASE_PORT
PUBLIC_BASE_URL=$(sq "${PUBLIC_BASE_URL:-http://$EC2_HOST:$BASE_PORT}")
KC_PORT=$KC_PORT
SPRING_PROFILES_ACTIVE=docker
DB_URL=jdbc:postgresql://postgres:5432/raiji_app
DB_USER=raiji
DB_PASSWORD=$(sq "$DB_PASSWORD")
POSTGRES_DB=raiji_keycloak
JWK_SET_URI=http://keycloak:8080/realms/raiji/protocol/openid-connect/certs
KEYCLOAK_ADMIN_CLIENT_SECRET=$(sq "$KEYCLOAK_ADMIN_CLIENT_SECRET")
AWS_REGION=$AWS_REGION_NAME
# --- funcionalidades opcionais (vazio = desligado) ---------------------------
INFO_SIMPLES_KEY=$(sq "${INFO_SIMPLES_KEY:-}")
INFOSIMPLES_GOVBR_CPF=$(sq "${INFOSIMPLES_GOVBR_CPF:-}")
INFOSIMPLES_GOVBR_SENHA=$(sq "${INFOSIMPLES_GOVBR_SENHA:-}")
RESEND_API_KEY=$(sq "${RESEND_API_KEY:-}")
RESEND_FROM=$(sq "${RESEND_FROM:-}")
STRIPE_API_KEY=$(sq "${STRIPE_API_KEY:-}")
STRIPE_WEBHOOK_SECRET=$(sq "${STRIPE_WEBHOOK_SECRET:-}")
RAIJI_CORS_ALLOWED_ORIGINS=$(sq "${RAIJI_CORS_ALLOWED_ORIGINS:-}")
DRIVER_DOCS_BUCKET=$(sq "$DRIVER_DOCS_BUCKET")
RAIJI_PLATFORM_FEE_PERCENT=$(sq "${RAIJI_PLATFORM_FEE_PERCENT:-1.5}")
RAIJI_TIME_ZONE=$(sq "${RAIJI_TIME_ZONE:-America/Sao_Paulo}")
ENV_EOF

# --- 5) Deploy remoto ----------------------------------------------------------
log "executando deploy-remote.sh na EC2 ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
  "cd '$REMOTE_DIR' && chmod +x scripts/aws/deploy-remote.sh scripts/smoke-test.sh && ./scripts/aws/deploy-remote.sh" \
  || die "deploy remoto falhou (health-gate). Se havia versão anterior, o self-rollback já foi tentado; veja logs acima."

log "deploy concluído com sucesso. URL: http://$EC2_HOST:$BASE_PORT"
CD_SETUP_EOF_09_523449a8

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/deploy-remote.sh" <<'CD_SETUP_EOF_10_b258b091'
#!/usr/bin/env bash
# =============================================================================
# deploy-remote.sh — roda NA instância EC2 (invocado via SSH pelo deploy.sh).
# Lê o .env do diretório, faz login no ECR, puxa a imagem nova, recria o stack,
# aguarda o health-gate e, se falhar, faz self-rollback para a imagem anterior.
# =============================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."   # /opt/raiji/<env>
[[ -f .env ]] || { echo "[remote] ERRO: .env não encontrado" >&2; exit 1; }
set -a; . ./.env; set +a

: "${COMPOSE_PROJECT_NAME:?}"
: "${IMAGE_TAG:?}"
: "${BASE_PORT:?}"

COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f docker-compose.aws.yml)
STATE_FILE=".deploy-state"
log() { echo "[remote:${ENVIRONMENT:-?}] $*"; }

# --- pré-requisito: Compose >= 2.24 (tags !override/!reset) --------------------
COMPOSE_VERSION="$(docker compose version --short 2>/dev/null || true)"
if [[ -z "$COMPOSE_VERSION" ]]; then
  echo "[remote] ERRO: 'docker compose' não encontrado. Instale docker-compose-v2." >&2
  exit 1
fi
if [[ "$(printf '%s\n2.24\n' "$COMPOSE_VERSION" | sort -V | head -n1)" != "2.24" ]]; then
  echo "[remote] ERRO: Docker Compose $COMPOSE_VERSION é antigo; o override AWS exige >= 2.24." >&2
  exit 1
fi
log "docker compose $COMPOSE_VERSION"

# --- registra a versão atualmente em execução (para rollback) ------------------
PREVIOUS_TAG=""
if [[ -f "$STATE_FILE" ]]; then
  PREVIOUS_TAG="$(sed -n 's/^IMAGE_TAG=//p' "$STATE_FILE" | tail -n1)"
fi
if [[ -n "$PREVIOUS_TAG" && "$PREVIOUS_TAG" != "$IMAGE_TAG" ]]; then
  cp "$STATE_FILE" ".deploy-state.previous"
  log "versão anterior registrada: $PREVIOUS_TAG"
fi

# --- login no ECR (usa o instance profile da EC2) ------------------------------
if command -v aws >/dev/null 2>&1; then
  aws ecr get-login-password --region "${AWS_REGION:-us-east-1}" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null \
    && log "login ECR ok" || log "AVISO: login ECR falhou (seguindo assim mesmo)"
fi

# --- pull + up ------------------------------------------------------------------
log "pull das imagens (IMAGE_TAG=$IMAGE_TAG) ..."
"${COMPOSE[@]}" pull --quiet app || { echo "[remote] ERRO: pull da imagem falhou" >&2; exit 1; }

log "subindo o stack ..."
UP_OK=1
"${COMPOSE[@]}" up -d --remove-orphans || UP_OK=0

if [[ "$UP_OK" -eq 1 ]]; then
  # grava estado atual
  grep -v '^IMAGE_TAG=' "$STATE_FILE" 2>/dev/null > "$STATE_FILE.tmp" || true
  echo "IMAGE_TAG=$IMAGE_TAG" >> "$STATE_FILE.tmp"
  echo "DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$STATE_FILE.tmp"
  mv "$STATE_FILE.tmp" "$STATE_FILE"

  # --- health-gate (reusa o smoke-test) ------------------------------------------
  # 90 x 10s = 15 min: primeiro boot (Keycloak import + Postgres + app) é lento.
  HEALTH_RETRIES="${HEALTH_RETRIES:-90}"
  log "aguardando health-gate em http://localhost:$BASE_PORT (até $((HEALTH_RETRIES * 10 / 60)) min) ..."
  if BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries "$HEALTH_RETRIES" --retry-delay 10; then
    log "health OK — deploy concluído."
    exit 0
  fi
  echo "[remote] ERRO: health-gate falhou." >&2
else
  echo "[remote] ERRO: 'docker compose up' falhou (veja logs acima)." >&2
fi

# --- self-rollback local ----------------------------------------------------------
if [[ -n "$PREVIOUS_TAG" ]]; then
  echo "[remote] self-rollback para $PREVIOUS_TAG ..." >&2
  IMAGE_TAG="$PREVIOUS_TAG" "${COMPOSE[@]}" up -d --no-deps --no-build app || true
  cp ".deploy-state.previous" "$STATE_FILE" 2>/dev/null || true
else
  # Sem versão anterior: NÃO servir versão quebrada — o app fica parado até
  # que um deploy correto seja publicado ("não deixe subir até subir certo").
  echo "[remote] SEM versão anterior — parando o app para não servir versão quebrada." >&2
  "${COMPOSE[@]}" stop app >/dev/null 2>&1 || true
fi
exit 1
CD_SETUP_EOF_10_b258b091

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/rollback.sh" <<'CD_SETUP_EOF_11_e9160336'
#!/usr/bin/env bash
# =============================================================================
# rollback.sh — procedimento de rollback da issue S1-03:
#   0) Verifica se EXISTE versão anterior (<env>-previous no ECR).
#      Sem anterior -> para o app na EC2 (não serve versão quebrada) e falha:
#      o ambiente fica offline até uma versão correta ser publicada.
#   1) RETAG no ECR: <env>-previous -> <env>-current (sem re-upload)
#   2) REDEPLOY na EC2 (compose pull + up -d + health-gate)
#   3) SMOKE TEST completo (paralelo) para confirmar a recuperação
#
# Uso (runner ou máquina com aws cli + acesso SSH):
#   ./scripts/aws/rollback.sh [--auto]
# Requer (env): ENVIRONMENT, EC2_HOST, EC2_USER, SSH_PRIVATE_KEY,
#               AWS_REGION_NAME, BASE_PORT, BASE_URL, SMOKE_USERNAME/SMOKE_PASSWORD
# =============================================================================
set -euo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório (dev|homolog)}"
: "${EC2_HOST:?EC2_HOST obrigatório}"
: "${EC2_USER:=ubuntu}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY obrigatório}"
: "${AWS_REGION_NAME:=us-east-1}"
: "${BASE_PORT:?BASE_PORT obrigatório}"
BASE_URL="${BASE_URL:-http://$EC2_HOST:$BASE_PORT}"
REPO="${ECR_REPOSITORY:-raiji-app}"

AUTO="${1:-}"
log() { echo "[rollback:$ENVIRONMENT] $*"; }
die() { echo "[rollback:$ENVIRONMENT] ERRO: $*" >&2; exit 1; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" || die "aws sts falhou"
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION_NAME}.amazonaws.com"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- SSH (chave temporária) ------------------------------------------------------
KEY_FILE="$(mktemp)"; umask 077; printf '%s\n' "$SSH_PRIVATE_KEY" > "$KEY_FILE"
cleanup() { rm -f "$KEY_FILE"; }
trap cleanup EXIT
SSH_OPTS=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)

# --- 0) Existe versão anterior? ----------------------------------------------------
log "verificando imagem anterior (${ENVIRONMENT}-previous) no ECR ..."
PREV="$(aws ecr describe-images --region "$AWS_REGION_NAME" --repository-name "$REPO" \
  --image-ids imageTag="${ENVIRONMENT}-previous" \
  --query 'imageDetails[0].imageTags[0]' --output text 2>/dev/null || true)"

if [[ -z "$PREV" || "$PREV" == "None" ]]; then
  log "NÃO existe versão anterior para reverter."
  log "Parando o app na EC2 para não servir versão quebrada ..."
  ssh "${SSH_OPTS[@]}" "$EC2_USER@$EC2_HOST" "ENVIRONMENT='$ENVIRONMENT' bash -s" <<'STOP_EOF' || true
set -u
cd "/opt/raiji/$ENVIRONMENT" 2>/dev/null || exit 0
set -a; . ./.env 2>/dev/null; set +a
docker compose -p "${COMPOSE_PROJECT_NAME:-raiji-$ENVIRONMENT}" \
  -f docker-compose.yml -f docker-compose.aws.yml stop app 2>/dev/null || true
STOP_EOF
  die "ambiente SEM histórico de rollback — mantido offline até que uma versão correta suba (release bloqueado)."
fi

# --- 1) RETAG da imagem anterior ---------------------------------------------------
log "retag ${ENVIRONMENT}-previous -> ${ENVIRONMENT}-current no ECR ..."
"$SCRIPT_DIR/ecr-retag.sh" "$ECR_REGISTRY/$REPO" \
  "${ENVIRONMENT}-previous" "${ENVIRONMENT}-current" --region "$AWS_REGION_NAME" \
  || die "retag falhou"

# --- 2) REDEPLOY na EC2 -------------------------------------------------------------
log "redeploy na EC2 ($EC2_USER@$EC2_HOST) ..."
ssh "${SSH_OPTS[@]}" "$EC2_USER@$EC2_HOST" "ENVIRONMENT='$ENVIRONMENT' bash -s" <<'REMOTE_EOF' || die "redeploy remoto falhou"
set -euo pipefail
cd "/opt/raiji/$ENVIRONMENT"
set -a; . ./.env; set +a
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f docker-compose.aws.yml)
aws ecr get-login-password --region "${AWS_REGION:-us-east-1}" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
"${COMPOSE[@]}" pull --quiet app
IMAGE_TAG="${ENVIRONMENT}-current" "${COMPOSE[@]}" up -d --no-deps --no-build app
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${ENVIRONMENT}-current/" .deploy-state 2>/dev/null || true
BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries "${HEALTH_RETRIES:-90}" --retry-delay 10
REMOTE_EOF

# --- 3) SMOKE completo (paralelo) ----------------------------------------------------
log "smoke test completo em $BASE_URL ..."
SMOKE_USERNAME="${SMOKE_USERNAME:-teste}" SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}" \
BASE_URL="$BASE_URL" "$SCRIPT_DIR/../smoke-test.sh" --parallel --retries 6 --retry-delay 10 \
  || die "smoke pós-rollback falhou — intervenção manual necessária (veja docs/DEPLOY.md)"

log "rollback concluído com sucesso. Ambiente restaurado: $BASE_URL"
[[ "$AUTO" == "--auto" ]] && log "(executado automaticamente)"
exit 0
CD_SETUP_EOF_11_e9160336

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/guardian.sh" <<'CD_SETUP_EOF_12_d732af27'
#!/usr/bin/env bash
# =============================================================================
# guardian.sh — vigia pós-deploy (proteção contra erros inesperados).
# Executado periodicamente pelo .github/workflows/guardian.yml:
#   1. Smoke completo (4 checagens em PARALELO)
#   2. Se falhar, aguarda GUARDIAN_RECHECK_DELAY e reconfirma (evita falso
#      positivo de rede/latência)
#   3. Falha confirmada + existe imagem <env>-previous -> ROLLBACK AUTOMÁTICO
#      (rollback.sh: retag + redeploy + smoke)
#   4. Sem histórico OU rollback falhou -> exit 1 (o workflow abre Issue)
#
# Requer (env): ENVIRONMENT, BASE_URL; para o rollback: EC2_HOST, EC2_USER,
#   SSH_PRIVATE_KEY, AWS_REGION_NAME, BASE_PORT, SMOKE_USERNAME/SMOKE_PASSWORD
# Saída: 0 = saudável (ou recuperado) | 1 = irrecuperável (gera alerta)
# =============================================================================
set -uo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório}"
: "${BASE_URL:?BASE_URL obrigatório}"

GUARDIAN_RECHECK_DELAY="${GUARDIAN_RECHECK_DELAY:-60}"
GUARDIAN_RETRY="${GUARDIAN_RETRY:-3}"
GUARDIAN_DELAY="${GUARDIAN_DELAY:-5}"
GUARDIAN_RETRY_RECHECK="${GUARDIAN_RETRY_RECHECK:-6}"
GUARDIAN_DELAY_RECHECK="${GUARDIAN_DELAY_RECHECK:-10}"
REPO="${ECR_REPOSITORY:-raiji-app}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { echo "[guardian:$ENVIRONMENT] $*"; }

smoke() { # <retries> <delay>
  BASE_URL="$BASE_URL" \
  SMOKE_USERNAME="${SMOKE_USERNAME:-teste}" \
  SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}" \
    "$SCRIPT_DIR/../smoke-test.sh" --parallel --retries "$1" --retry-delay "$2"
}

log "smoke periódico em $BASE_URL"
if smoke "$GUARDIAN_RETRY" "$GUARDIAN_DELAY"; then
  log "ambiente saudável ✔"
  exit 0
fi

log "smoke FALHOU — reconfirmando em ${GUARDIAN_RECHECK_DELAY}s (evitar falso positivo) ..."
sleep "$GUARDIAN_RECHECK_DELAY"
if smoke "$GUARDIAN_RETRY_RECHECK" "$GUARDIAN_DELAY_RECHECK"; then
  log "falha transitória — ambiente saudável na reconfirmação ✔"
  exit 0
fi

log "falha CONFIRMADA. Verificando versão anterior no ECR ..."
PREV="$(aws ecr describe-images --region "${AWS_REGION_NAME:-us-east-1}" \
  --repository-name "$REPO" --image-ids imageTag="${ENVIRONMENT}-previous" \
  --query 'imageDetails[0].imageTags[0]' --output text 2>/dev/null || true)"

if [[ -n "$PREV" && "$PREV" != "None" ]]; then
  log "existe versão anterior (${ENVIRONMENT}-previous) — rollback automático ..."
  if "$SCRIPT_DIR/rollback.sh" --auto; then
    log "ambiente RECUPERADO via rollback ✔"
    exit 0
  fi
  log "rollback FALHOU — intervenção manual necessária."
  exit 1
fi

log "SEM versão anterior — impossível reverter automaticamente; gerando alerta."
exit 1
CD_SETUP_EOF_12_d732af27

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/ecr-retag.sh" <<'CD_SETUP_EOF_13_b46361db'
#!/usr/bin/env bash
# =============================================================================
# ecr-retag.sh — movc uma tag de imagem no ECR sem re-upload (retag).
# Uso: ecr-retag.sh <repo-completo> <tag-origem> <tag-destino> [--region r] [--if-missing=skip|fail]
# Exemplo: ecr-retag.sh 123.dkr.ecr.us-east-1.amazonaws.com/raiji-app homolog-previous homolog-current
# =============================================================================
set -euo pipefail

REPO="${1:?uso: ecr-retag.sh <repo> <tag-origem> <tag-destino>}"
SRC="${2:?tag de origem ausente}"
DST="${3:?tag de destino ausente}"
shift 3
REGION="${AWS_REGION_NAME:-${AWS_REGION:-us-east-1}}"
IF_MISSING="fail"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --if-missing=*) IF_MISSING="${1#*=}"; shift ;;
    *) echo "opção inválida: $1" >&2; exit 2 ;;
  esac
done

REPO_NAME="${REPO##*/}"

MANIFEST="$(aws ecr batch-get-image --region "$REGION" \
  --repository-name "$REPO_NAME" --image-ids imageTag="$SRC" \
  --query 'images[0].imageManifest' --output text 2>/dev/null || true)"

if [[ -z "$MANIFEST" || "$MANIFEST" == "None" ]]; then
  if [[ "$IF_MISSING" == "skip" ]]; then
    echo "[ecr-retag] tag '$SRC' não existe em $REPO_NAME — nada a fazer (primeiro deploy?)."
    exit 0
  fi
  echo "[ecr-retag] ERRO: tag '$SRC' não encontrada em $REPO_NAME." >&2
  exit 1
fi

if aws ecr put-image --region "$REGION" --repository-name "$REPO_NAME" \
     --image-tag "$DST" --image-manifest "$MANIFEST" >/dev/null 2>&1; then
  echo "[ecr-retag] $REPO_NAME:$SRC -> $REPO_NAME:$DST (retag OK)"
else
  # ImageAlreadyExistsException: a tag destino já aponta para a mesma imagem.
  echo "[ecr-retag] $REPO_NAME:$DST já aponta para esta imagem (ok)."
fi
CD_SETUP_EOF_13_b46361db

  mkdir -p "$dst/scripts/aws"
  cat > "$dst/scripts/aws/check-github-config.sh" <<'CD_SETUP_EOF_14_4884cc05'
#!/usr/bin/env bash
# =============================================================================
# check-github-config.sh — diagnostica o que falta no GitHub para o CD rodar.
#
# Somente LEITURA (não altera nada). Requer `gh` autenticado com permissão de
# admin no repositório (para listar secrets/variables/environments):
#
#   gh auth login
#   ./scripts/aws/check-github-config.sh
#
# Saída: OK/FALTA para cada item + dica de onde configurar.
# Exit code: 0 = tudo pronto; 1 = falta algo; 2 = gh ausente/sem auth.
# =============================================================================
set -uo pipefail

if ! command -v gh >/dev/null 2>&1; then
  echo "ERRO: gh (GitHub CLI) não instalado." >&2
  echo "Instale: https://cli.github.com/  (ou use o checklist manual em docs/CD-CHECKLIST.md)" >&2
  exit 2
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "ERRO: gh não autenticado. Rode: gh auth login" >&2
  exit 2
fi

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" || {
  echo "ERRO: não foi possível detectar o repositório (rode dentro do clone)." >&2
  exit 2
}
DEFAULT_BRANCH="$(gh repo view --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null || echo main)"

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
FAIL=0
ok()   { printf '  %sOK%s    %s\n' "$GRN" "$NC" "$*"; }
bad()  { printf '  %sFALTA%s %s\n' "$RED" "$NC" "$*"; FAIL=1; }
warn() { printf '  %sAVISO%s %s\n' "$YLW" "$NC" "$*"; }

has_secret() { # repo|env, name
  local scope="$1" name="$2"
  if [[ "$scope" == "repo" ]]; then
    gh api "repos/$REPO/actions/secrets" --jq '.secrets[].name' 2>/dev/null | grep -qx "$name"
  else
    gh api "repos/$REPO/environments/$scope/secrets" --jq '.secrets[].name' 2>/dev/null | grep -qx "$name"
  fi
}
has_var() {
  gh api "repos/$REPO/actions/variables" --jq '.variables[].name' 2>/dev/null | grep -qx "$1"
}
has_env() {
  gh api "repos/$REPO/environments" --jq '.environments[].name' 2>/dev/null | grep -qx "$1"
}

echo
echo "Repositório: $REPO (branch default: $DEFAULT_BRANCH)"
echo

echo "[1] Secrets do repositório (Actions)"
for s in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  if has_secret repo "$s"; then ok "$s"; else bad "$s — Settings -> Secrets and variables -> Actions -> Secrets"; fi
done

echo
echo "[2] Variables do repositório (Actions)"
for v in AWS_EC2_HOST_DEV AWS_EC2_HOST_HOMOLOG; do
  if has_var "$v"; then ok "$v"; else bad "$v — Settings -> Secrets and variables -> Actions -> Variables"; fi
done

for env in dev homolog; do
  suffix="$(echo "$env" | tr '[:lower:]' '[:upper:]')"
  echo
  echo "[3] Secrets por ambiente ($env) — repositório OU environment"
  for s in "AWS_SSH_KEY_$suffix" "DB_PASSWORD_$suffix" "KEYCLOAK_ADMIN_CLIENT_SECRET_$suffix"; do
    if has_secret repo "$s"; then
      ok "$s (repositório)"
    elif has_env "$env" && has_secret "$env" "$s"; then
      ok "$s (environment '$env')"
    else
      bad "$s — Settings -> Secrets and variables -> Actions -> Secrets (repositório; recomendado) ou no Environment '$env'"
    fi
  done
  for s in "SMOKE_USERNAME_$suffix" "SMOKE_PASSWORD_$suffix"; do
    if has_secret repo "$s" || { has_env "$env" && has_secret "$env" "$s"; }; then
      ok "$s (opcional)"
    else
      warn "$s (opcional) — padrão teste/teste123"
    fi
  done
  echo
  echo "[3b] Environment '$env' (aprovação manual)"
  if has_env "$env"; then
    ok "environment existe"
  else
    warn "environment '$env' não existe — crie em Settings -> Environments somente se quiser 'Required reviewers' (não é obrigatório)"
  fi
done

echo
echo "[4] Funcionalidades opcionais (repository secrets)"
for s in INFO_SIMPLES_KEY INFOSIMPLES_GOVBR_CPF INFOSIMPLES_GOVBR_SENHA \
         RESEND_API_KEY RESEND_FROM STRIPE_API_KEY STRIPE_WEBHOOK_SECRET \
         RAIJI_CORS_ALLOWED_ORIGINS; do
  if has_secret repo "$s"; then ok "$s"; else warn "$s (não configurado — recurso fica desligado)"; fi
done

echo
echo "[5] Workflows visíveis no GitHub"
for w in "CI" "Deploy DEV (develop -> AWS dev)" "Deploy HOMOLOG (main -> AWS sistema)" "Rollback (manual)" "Guardian (smoke contínuo + rollback automático)"; do
  if gh api "repos/$REPO/actions/workflows" --jq '.workflows[].name' 2>/dev/null | grep -qxF "$w"; then
    ok "$w"
  else
    bad "$w — não registrado. Os workflows só aparecem no branch default ($DEFAULT_BRANCH)."
  fi
done

echo
if [[ "$FAIL" -eq 0 ]]; then
  printf '%sTudo pronto para o CD. ✅%s\n' "$GRN" "$NC"
  exit 0
fi
printf '%sFaltam itens acima. Veja docs/CD-CHECKLIST.md.%s\n' "$RED" "$NC"
exit 1
CD_SETUP_EOF_14_4884cc05

  mkdir -p "$dst"
  cat > "$dst/docker-compose.aws.yml" <<'CD_SETUP_EOF_15_4d1758c6'
# =============================================================================
# Override de deploy na AWS (EC2). Uso (na instância):
#   docker compose -p raiji-<env> -f docker-compose.yml -f docker-compose.aws.yml up -d
#
# Variáveis (arquivo .env gerado pelo scripts/aws/deploy.sh — NUNCA commitar):
#   ECR_REGISTRY, ECR_REPOSITORY, IMAGE_TAG, BASE_PORT, KC_PORT,
#   DB_URL, DB_USER, DB_PASSWORD, JWK_SET_URI, KEYCLOAK_ADMIN_CLIENT_SECRET,
#   SPRING_PROFILES_ACTIVE, POSTGRES_DB, PUBLIC_BASE_URL (URL pública do Kong)
#
# Requer Docker Compose >= 2.24 (tags !override/!reset).
# =============================================================================
services:
  postgres:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-postgres
    environment:
      POSTGRES_USER: ${DB_USER:-raiji}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD obrigatório}
      POSTGRES_DB: ${POSTGRES_DB:-raiji_keycloak}
    ports: !override []          # banco nunca exposto publicamente

  keycloak:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-keycloak
    environment:
      KC_DB_USERNAME: ${DB_USER:-raiji}
      KC_DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD obrigatório}
    ports: !override
      - "${KC_PORT:-8180}:8080"

  app:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-app
    # O contexto de build (raiji-app/src) NÃO é enviado para a EC2: a imagem
    # vem SEMPRE do ECR. `!reset` remove o `build:` herdado do compose base.
    build: !reset null
    image: ${ECR_REGISTRY:?ECR_REGISTRY obrigatório}/${ECR_REPOSITORY:-raiji-app}:${IMAGE_TAG:?IMAGE_TAG obrigatório}
    pull_policy: always
    environment:
      # --- variáveis exigidas pela issue S1-03 -------------------------------
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-docker}
      KEYCLOAK_ADMIN_CLIENT_SECRET: ${KEYCLOAK_ADMIN_CLIENT_SECRET:?KEYCLOAK_ADMIN_CLIENT_SECRET obrigatório}
      DB_URL: ${DB_URL:-jdbc:postgresql://postgres:5432/raiji_app}
      DB_USER: ${DB_USER:-raiji}
      DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD obrigatório}
      JWK_SET_URI: ${JWK_SET_URI:-http://keycloak:8080/realms/raiji/protocol/openid-connect/certs}
      # --- mapeamento para as propriedades Spring ----------------------------
      SPRING_DATASOURCE_URL: ${DB_URL:-jdbc:postgresql://postgres:5432/raiji_app}
      SPRING_DATASOURCE_USERNAME: ${DB_USER:-raiji}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD obrigatório}
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: ${JWK_SET_URI:-http://keycloak:8080/realms/raiji/protocol/openid-connect/certs}
      RAIJI_KEYCLOAK_ADMIN_CLIENT_SECRET: ${KEYCLOAK_ADMIN_CLIENT_SECRET:?KEYCLOAK_ADMIN_CLIENT_SECRET obrigatório}
      # --- região AWS (usada pelo S3 da CNH via instance profile) -------------
      AWS_REGION: ${AWS_REGION:-us-east-1}
      # --- verificação da CNH: S3 real (sem endpoint/chaves: role da EC2) ------
      INFO_SIMPLES_KEY: ${INFO_SIMPLES_KEY:-}
      INFOSIMPLES_GOVBR_CPF: ${INFOSIMPLES_GOVBR_CPF:-}
      INFOSIMPLES_GOVBR_SENHA: ${INFOSIMPLES_GOVBR_SENHA:-}
      # --- e-mails transacionais (Resend) ------------------------------------
      RESEND_API_KEY: ${RESEND_API_KEY:-}
      RESEND_FROM: ${RESEND_FROM:-}
      RESEND_BASE_URL: ${RESEND_BASE_URL:-https://api.resend.com}
      # --- Stripe (caronas pagas / estorno) ----------------------------------
      STRIPE_API_KEY: ${STRIPE_API_KEY:-}
      STRIPE_WEBHOOK_SECRET: ${STRIPE_WEBHOOK_SECRET:-}
      # --- regras de negócio -------------------------------------------------
      RAIJI_PLATFORM_FEE_PERCENT: ${RAIJI_PLATFORM_FEE_PERCENT:-1.5}
      RAIJI_INTERMUNICIPAL_REQUIRES_BUSINESS: ${RAIJI_INTERMUNICIPAL_REQUIRES_BUSINESS:-false}
      RAIJI_TIME_ZONE: ${RAIJI_TIME_ZONE:-America/Sao_Paulo}
      # Origens extras do app (além de localhost/capacitor), separadas por vírgula
      RAIJI_CORS_ALLOWED_ORIGINS: ${RAIJI_CORS_ALLOWED_ORIGINS:-}
      # --- S3 da CNH (produção: bucket real, credenciais da role da EC2) ------
      DRIVER_DOCS_BUCKET: ${DRIVER_DOCS_BUCKET:-raiji-driver-docs}
      DRIVER_DOCS_S3_ENDPOINT: ""
      DRIVER_DOCS_S3_PATH_STYLE: "false"
      DRIVER_DOCS_S3_ACCESS_KEY: ""
      DRIVER_DOCS_S3_SECRET_KEY: ""
    ports: !override []          # app acessível apenas via Kong

  # S3 local (RustFS) só no desenvolvimento: na AWS usa-se o S3 de verdade
  rustfs:
    profiles: ["local-only"]
    ports: !override []

  rustfs-init:
    profiles: ["local-only"]

  rabbitmq:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-rabbitmq
    ports: !override []          # broker e painel só na rede interna

  kong-init:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-kong-init

  kong:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-kong
    ports: !override
      - "${BASE_PORT:-8000}:8000"   # única entrada pública do ambiente
CD_SETUP_EOF_15_4d1758c6

  mkdir -p "$dst/infra/aws"
  cat > "$dst/infra/aws/aws-config.env" <<'CD_SETUP_EOF_16_2ee62e1a'
# =============================================================================
# CONFIGURAÇÃO AWS — fonte única (fácil de mudar).
#
# Quem lê este arquivo:
#   * scripts/aws/bootstrap-aws.sh   (provisiona/redimensiona a infra;
#                                     flags de CLI têm precedência)
#   * .github/workflows/deploy.yml   (job `config`: região + portas do deploy)
#   * .github/workflows/rollback.yml (rollback manual)
#   * .github/workflows/guardian.yml (vigia contínuo pós-deploy)
#
# Como aplicar mudanças:
#   - TIPO DE MÁQUINA: edite INSTANCE_TYPE_* e rerode
#     `./scripts/aws/bootstrap-aws.sh --env <env>` — ele detecta a diferença,
#     para a instância, aplica o novo tipo e religa (resize automático).
#   - PORTAS: commit + push (os workflows leem no próximo deploy) e rerode o
#     bootstrap para ajustar o security group.
#   - REGIÃO: rerode o bootstrap para o ambiente (cria infra na região nova).
# =============================================================================

# --- Global -------------------------------------------------------------------
AWS_REGION=us-east-1

# --- Ambiente DEV (branch develop) ---------------------------------------------
INSTANCE_TYPE_DEV=t3.small       # tipo da máquina (t3.micro, t3.medium, ...)
BASE_PORT_DEV=8000               # porta pública (Kong) do ambiente
KC_PORT_DEV=8180                 # porta do Keycloak (acesso admin)
AMI_DEV=                         # vazio = Ubuntu 24.04 LTS mais recente

# --- Ambiente HOMOLOG (branch main — "sistema") ---------------------------------
INSTANCE_TYPE_HOMOLOG=t3.small
BASE_PORT_HOMOLOG=9000
KC_PORT_HOMOLOG=9180
AMI_HOMOLOG=
CD_SETUP_EOF_16_2ee62e1a

  mkdir -p "$dst/docs"
  cat > "$dst/docs/CD-CHECKLIST.md" <<'CD_SETUP_EOF_17_13a5a446'
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
> Por padrão ele NÃO publica develop/main — atua só na branch atual (ex.:
> fix/cd-deploy-hardening). Para publicar e disparar o Deploy DEV:
> `--push-develop --watch` (pede confirmação; `--yes` pula). Com `--sync-main`
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
CD_SETUP_EOF_17_13a5a446

  mkdir -p "$dst/docs"
  cat > "$dst/docs/DEPLOY.md" <<'CD_SETUP_EOF_18_b4adc42d'
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

> **Atalho:** `docs/CD-CHECKLIST.md` tem o passo a passo enxuto e
> `./scripts/aws/check-github-config.sh` (com `gh` autenticado) lista o que
> falta. O `main` atual **não tem os workflows**: sincronize-o (seção 4.4)
> para que `Deploy HOMOLOG`, `Rollback` e `Guardian` apareçam.

### 4.1 Bootstrap da infra AWS

```bash
# na sua máquina, com aws cli v2 configurado (permissões EC2/IAM/ECR)
./scripts/aws/bootstrap-aws.sh --env dev
./scripts/aws/bootstrap-aws.sh --env homolog
```

O script cria ECR (`raiji-app`), bucket S3 privado da CNH, security group,
key pair, IAM role (pull ECR + S3), IAM user do Actions (`raiji-actions`,
access key em `~/.ssh/raiji-actions-access-key.json`), Elastic IP e a
instância EC2 com Docker instalado, e **imprime no final exatamente os
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

**Secrets por ambiente — REPOSITÓRIO (recomendado)** (aba *Secrets*):

| Segredo | Valor |
|---|---|
| `AWS_SSH_KEY_DEV` / `AWS_SSH_KEY_HOMOLOG` | conteúdo da `.pem` gerada no bootstrap |
| `DB_PASSWORD_DEV` / `DB_PASSWORD_HOMOLOG` | senha forte do Postgres/Keycloak |
| `KEYCLOAK_ADMIN_CLIENT_SECRET_DEV` / `_HOMOLOG` | segredo do client `raiji-admin` (`raiji-admin-secret`) |
| `SMOKE_USERNAME_DEV` / `SMOKE_PASSWORD_DEV` | padrão: `teste` / `teste123` (opcionais) |
| `SMOKE_USERNAME_HOMOLOG` / `SMOKE_PASSWORD_HOMOLOG` | idem — **troque em homolog** |

> **Por quê no repositório:** o job que chama o workflow reutilizável não
> pode declarar `environment:` (limitação do GitHub), então um secret que
> exista só no Environment chegaria vazio ali. O `deploy.yml` tem fallback
> que também resolve Environment secrets (os jobs internos declaram
> `environment`), mas o repositório funciona em todos os caminhos.

**Environments `dev` e `homolog` (opcional — aprovação/auditoria)**:

- No `homolog`, ative **Required reviewers** para exigir aprovação manual
  antes do deploy do sistema.
- *Environment secrets* com os mesmos nomes também funcionam (fallback),
  mas não duplique valores diferentes nos dois escopos.

**Secrets opcionais do repositório** (funcionalidades da aplicação — vazio =
recurso desligado):

| Segredo | Recurso |
|---|---|
| `INFO_SIMPLES_KEY`, `INFOSIMPLES_GOVBR_CPF`, `INFOSIMPLES_GOVBR_SENHA` | verificação de CNH (Infosimples/gov.br) |
| `RESEND_API_KEY`, `RESEND_FROM` | e-mails transacionais |
| `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` | caronas pagas / estorno |
| `RAIJI_CORS_ALLOWED_ORIGINS` | origens web extras (separadas por vírgula) |

> Esses valores são **repository secrets**: o job que chama o workflow
> reutilizável não pode declarar `environment:` (limitação do GitHub).

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

> O `bootstrap-aws.sh` já cria esse usuário e aplica a policy automaticamente.

### 4.4 Sincronizar o branch `main` (obrigatório uma vez)

O `main` atual está atrás do `develop` e **não contém `.github/workflows`**.
Enquanto isso, só `CI` e `Deploy DEV` aparecem no GitHub. Depois de
configurar os secrets/variables (4.2):

```bash
git fetch origin
git push origin origin/develop:main   # fast-forward (main é ancestral de develop)
```

> Isso dispara o `Deploy HOMOLOG`. Se quiser evitar o deploy imediato, faça o
> push com o environment `homolog` sem required reviewers apenas após o
> bootstrap, ou use `workflow_dispatch` depois.

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
| `PUBLIC_BASE_URL` | `http://<host>:<BASE_PORT>` | issuer do Keycloak/Kong |
| `DRIVER_DOCS_BUCKET` | `raiji-driver-docs-<conta>-<env>` | bucket S3 da CNH (criado no bootstrap) |
| `INFO_SIMPLES_KEY`, `INFOSIMPLES_GOVBR_*` | vazio | verificação de CNH |
| `RESEND_API_KEY`, `RESEND_FROM` | vazio | e-mails |
| `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` | vazio | pagamentos |
| `RAIJI_CORS_ALLOWED_ORIGINS` | vazio | CORS extra |
| `RAIJI_PLATFORM_FEE_PERCENT`, `RAIJI_TIME_ZONE` | `1.5`, `America/Sao_Paulo` | regras de negócio |

> O `.env` é gravado com aspas seguras (`deploy.sh`), então senhas com `$`,
> espaços ou aspas não quebram o Compose.

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

> O `scripts/setup-cicd.sh` **não gera mais arquivos**: ele apenas verifica a
> presença dos arquivos de CI/CD. Antes ele sobrescrevia workflows/scripts com
> uma versão antiga e destruía correções (ex.: `build: !reset`). Rodar
> `tests/cicd/run-all.sh` agora é seguro.

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
* **Instância única por ambiente:** sem HA. O `bootstrap-aws.sh` aloca e
  associa um **Elastic IP** por ambiente, então o host não muda em
  stop/start (a variable do GitHub continua válida).
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
- [ ] `git push origin origin/develop:main` (workflows no branch default)
- [ ] `./scripts/aws/check-github-config.sh` sem itens FALTA
- [ ] (Opcional) Required reviewers no environment `homolog`
- [ ] (Recomendado) Branch protection em `main`/`develop` exigindo o status `CI`
- [ ] Push em `develop` → workflow **Deploy DEV** verde → smoke OK
- [ ] Push em `main` → workflow **Deploy HOMOLOG** verde → URL acessível
- [ ] Rollback manual testado uma vez (Actions → *Rollback (manual)*)
- [ ] Guardian testado uma vez (Actions → *Guardian* → `all`)
CD_SETUP_EOF_18_b4adc42d

}

# ========================================================================
# FASE 1 · APPLY — implementa as correções DENTRO do projeto
# ========================================================================
phase 1 "APPLY — implementar as correções do CD nos arquivos do projeto"
APPLIED=0; UNCHANGED=0; APPLY_LIST=()
BACKUP_DIR="$SECRETS_DIR/backup-$STAMP"
STAGE_DIR="$SECRETS_DIR/payload-$STAMP"

if ! phase_wanted apply; then
  info "fase apply pulada por opção (--only/--skip)"
else
  if [[ "$DRY_RUN" == 1 ]]; then
    mkdir -p "$STAGE_DIR"
    payload_stage "$STAGE_DIR"
    info "[dry-run] comparando payload com os arquivos atuais (nada será escrito)"
  else
    mkdir -p "$STAGE_DIR"
    payload_stage "$STAGE_DIR"
  fi

  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    target="$ROOT/$rel"
    if [[ "$rel" == "infra/aws/aws-config.env" && -f "$target" ]]; then
      if ! cmp -s "$STAGE_DIR/$rel" "$target"; then
        warn "infra/aws/aws-config.env JÁ EXISTE e foi mantido (sua configuração: tipo de máquina/portas/região). O payload só cria se faltar."
      else
        info "igual: $rel"
        UNCHANGED=$((UNCHANGED+1))
      fi
      continue
    fi
    if [[ -f "$target" ]] && cmp -s "$STAGE_DIR/$rel" "$target"; then
      info "igual: $rel"
      UNCHANGED=$((UNCHANGED+1))
      continue
    fi
    if [[ "$DRY_RUN" == 1 ]]; then
      if [[ -f "$target" ]]; then info "[dry-run] atualizaria (backup em $BACKUP_DIR): $rel"
      else info "[dry-run] criaria: $rel"; fi
      APPLY_LIST+=("$rel")
      continue
    fi
    mkdir -p "$(dirname "$target")"
    if [[ -f "$target" ]]; then
      mkdir -p "$BACKUP_DIR/$(dirname "$rel")"
      cp "$target" "$BACKUP_DIR/$rel"
      info "atualizado (backup: $BACKUP_DIR/$rel): $rel"
    else
      info "criado: $rel"
    fi
    cp "$STAGE_DIR/$rel" "$target"
    case "$rel" in
      scripts/*.sh) chmod 755 "$target" ;;
      cd-setup-all.sh) chmod 755 "$target" ;;
      *) chmod 644 "$target" ;;
    esac
    APPLY_LIST+=("$rel")
    APPLIED=$((APPLIED+1))
  done < <(printf '%s\n' "${PAYLOAD_FILES[@]}")

  # garante o .gitignore (nunca sobrescreve; só acrescenta o que falta)
  GITIGNORE_NEEDED=0
  if [[ -f "$ROOT/.gitignore" ]]; then
    for pat in ".cd-secrets-local/" ".deploy-state" "*.pem" ".env"; do
      grep -qxF "$pat" "$ROOT/.gitignore" || { GITIGNORE_NEEDED=1; break; }
    done
    if [[ "$DRY_RUN" == 0 && "$GITIGNORE_NEEDED" == 1 ]]; then
      { echo ""; echo "# --- CD (cd-setup-all.sh) ---";
        grep -qxF ".cd-secrets-local/" "$ROOT/.gitignore" || echo ".cd-secrets-local/";
        grep -qxF ".deploy-state" "$ROOT/.gitignore" || echo ".deploy-state";
        grep -qxF ".deploy-state.previous" "$ROOT/.gitignore" || echo ".deploy-state.previous";
        grep -qxF "*.pem" "$ROOT/.gitignore" || echo "*.pem";
        grep -qxF ".env" "$ROOT/.gitignore" || echo ".env"; } >> "$ROOT/.gitignore"
      info ".gitignore atualizado (segredos locais ignorados)"
      APPLY_LIST+=(".gitignore")
    fi
  else
    if [[ "$DRY_RUN" == 0 ]]; then
      printf '# --- CD (cd-setup-all.sh) ---\n.cd-secrets-local/\n.deploy-state\n.deploy-state.previous\n*.pem\n.env\n' > "$ROOT/.gitignore"
      info ".gitignore criado (segredos locais ignorados)"
      APPLY_LIST+=(".gitignore")
    fi
  fi

  if [[ "$DRY_RUN" == 1 ]]; then
    info "[dry-run] arquivos que seriam criados/atualizados: ${#APPLY_LIST[@]}"
  else
    printf '%s\n' "${APPLY_LIST[@]}" > "$SECRETS_DIR/applied-$STAMP.list" 2>/dev/null || true
    if [[ "$APPLIED" -gt 0 ]]; then
      done_item "APPLY: $APPLIED arquivo(s) implementado(s) no projeto ($UNCHANGED já corretos) — backup em $BACKUP_DIR"
    else
      done_item "APPLY: nada a mudar — os $UNCHANGED arquivos do CD já estão na versão correta"
    fi
  fi
fi

# ========================================================================
# FASE 2 · VERIFY — as correções ficaram aplicadas?
# ========================================================================
phase 2 "VERIFY — conferência das correções"
VERIFY_OK=1
if ! phase_wanted verify; then
  info "fase verify pulada por opção (--only/--skip)"
else
  check_marker() { # arquivo, grep, descrição
    local file="$1" pat="$2" desc="$3"
    if grep -q "$pat" "$file" 2>/dev/null; then info "correção presente: $desc"
    else missing_item "correção ausente: $desc (em $file)"; VERIFY_OK=0; fi
  }
  check_marker .github/workflows/deploy-dev.yml     'environment: dev'     "caller DEV declara environment (secrets por ambiente chegam preenchidos)"
  check_marker .github/workflows/deploy-homolog.yml 'environment: homolog' "caller HOMOLOG declara environment"
  check_marker .github/workflows/deploy.yml         'AWS_SSH_KEY_DEV'      "deploy.yml com fallback duplo de escopo (repositório OU environment)"
  check_marker docker-compose.aws.yml               'build: !reset null'   "compose AWS usa imagem do ECR (build desativado)"
  check_marker docker-compose.aws.yml               'pull_policy: always'  "compose AWS sempre puxa a imagem nova"
  check_marker scripts/setup-cicd.sh                'NÃO gera mais arquivos' "setup-cicd.sh não sobrescreve correções (modo verificação)"
  check_marker scripts/aws/bootstrap-aws.sh         'raiji-actions'        "bootstrap cria IAM user do GitHub Actions"
  check_marker scripts/smoke-test.sh                'SMOKE_USERNAME'       "smoke-test configurável por secrets"

  if [[ "$VERIFY_OK" == 1 ]]; then
    done_item "VERIFY: todas as correções confirmadas nos arquivos do projeto"
  else
    decision "Alguma correção não ficou aplicada — rode de novo: ./cd-setup-all.sh --only apply"
  fi
fi

# ========================================================================
# FASE 3 · AWS — conexão + provisionamento (bootstrap idempotente)
# ========================================================================
phase 3 "AWS — conexão e provisionamento (dev/homolog)"
AWS_CONNECTED=0; BOOTSTRAP_RESULT=""; ACTIONS_ID=""; ACTIONS_SEC=""

if ! phase_wanted aws; then
  info "fase aws pulada por opção (--only/--skip)"
else
  # 3.1 resolver credenciais: flags > env > profile > config existente
  CREDS_SOURCE=""
  if [[ -n "$AWS_KEY" && -n "$AWS_SECRET" ]]; then
    export AWS_ACCESS_KEY_ID="$AWS_KEY" AWS_SECRET_ACCESS_KEY="$AWS_SECRET"
    CREDS_SOURCE="flags"
  elif [[ -n "${AWS_ACCESS_KEY_ID:-}" && -n "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
    CREDS_SOURCE="env"
  elif [[ -n "$AWS_PROFILE_OPT" ]]; then
    export AWS_PROFILE="$AWS_PROFILE_OPT"; CREDS_SOURCE="profile $AWS_PROFILE_OPT"
  else
    CREDS_SOURCE="config existente (se houver)"
  fi

  if ! command -v aws >/dev/null 2>&1; then
    missing_item "aws cli não instalado nesta máquina (AWS CLI v2: https://docs.aws.amazon.com/cli)"
    decision "Instalar AWS CLI v2 (ou rodar este script numa máquina que o tenha)."
  elif [[ "$DRY_RUN" == 1 ]]; then
    info "[dry-run] aws sts get-caller-identity"
    AWS_CONNECTED=1   # simula conexão para pré-visualizar o resto do script
    EC2_IP_DEV="203.0.113.10"; EC2_IP_HOMOLOG="203.0.113.11"
    PEM_DEV="$HOME/.ssh/raiji-dev-pem"; PEM_HOMOLOG="$HOME/.ssh/raiji-homolog-pem"
    ACTIONS_ID="AKIADRYRUNEXAMPLE"; ACTIONS_SEC="dry-run-example-secret"
    info "[dry-run] credenciais/IPs/chaves simulados para pré-visualização"
  elif ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" \
       && [[ "$ACCOUNT_ID" != "None" && -n "$ACCOUNT_ID" ]]; then
    ok "conectado à AWS: conta=$ACCOUNT_ID (credenciais: $CREDS_SOURCE)"
    save_secret_line "# AWS: conta $ACCOUNT_ID (credenciais: $CREDS_SOURCE)"
    AWS_CONNECTED=1
  else
    missing_item "credenciais AWS válidas não encontradas (fase AWS pulada)"
    decision "Enviar credenciais AWS COM permissões EC2 + IAM + ECR + S3: rode de novo com --aws-key/--aws-secret (ou 'aws configure' na sua máquina). NÃO use o usuário root — crie um IAM user admin temporário."
  fi

  if [[ "$AWS_CONNECTED" == 1 && "$DRY_RUN" == 0 ]]; then
    for env in $ENVS; do
      SUF="$(echo "$env" | tr '[:lower:]' '[:upper:]')"
      LOGF="$SECRETS_DIR/bootstrap-$env-$STAMP.log"
      step "bootstrap do ambiente $env (log completo: $LOGF)"
      if ./scripts/aws/bootstrap-aws.sh --env "$env" 2>&1 | tee "$LOGF"; then
        done_item "AWS bootstrap [$env]: ECR + S3 CNH + IAM + SG + key pair + EC2 + Elastic IP"
        BOOTSTRAP_RESULT="${BOOTSTRAP_RESULT}${env}(ok) "
      else
        missing_item "AWS bootstrap [$env] FALHOU — veja $LOGF (a credencial tem permissão EC2/IAM/ECR/S3?)"
        BOOTSTRAP_RESULT="${BOOTSTRAP_RESULT}${env}(falhou) "
      fi
      # Elastic IP do ambiente
      IP="$(aws ec2 describe-addresses --region "${AWS_REGION_CFG:-us-east-1}" \
            --filters "Name=tag:RaijiEnv,Values=$env" \
            --query 'Addresses[0].PublicIp' --output text 2>/dev/null || true)"
      if [[ -n "$IP" && "$IP" != "None" ]]; then
        eval "EC2_IP_$SUF=\"$IP\""
        ok "IP elástico [$env]: $IP"
        save_secret_line "AWS_EC2_HOST_$SUF=$IP"
      else
        eval "EC2_IP_$SUF=\"\""
        missing_item "IP elástico [$env] não encontrado (bootstrap falhou para este ambiente?)"
      fi
      # chave SSH
      KEY_FILE="$HOME/.ssh/raiji-$env-pem"
      if [[ -f "$KEY_FILE" ]]; then
        eval "PEM_$SUF=\"$KEY_FILE\""; ok "chave SSH [$env]: $KEY_FILE"
      else
        eval "PEM_$SUF=\"\""
        missing_item "chave SSH [$env] ausente: $KEY_FILE (foi criada por um bootstrap anterior em OUTRA máquina?)"
        decision "Enviar a .pem do ambiente $env e salvar em $KEY_FILE (chmod 600), ou rodar o bootstrap nesta máquina."
      fi
      # teste rápido de SSH (não bloqueia; user-data instala Docker em ~2-5 min)
      if [[ -f "$KEY_FILE" && -n "$IP" && "$IP" != "None" ]]; then
        if timeout 15 ssh -i "$KEY_FILE" -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new \
             -o BatchMode=yes "ubuntu@$IP" 'docker --version' >/dev/null 2>&1; then
          ok "SSH [$env]: Docker já instalado na instância"
        else
          warn "SSH [$env]: instância ainda instalando Docker (user-data ~2-5 min) — o deploy espera isso sozinho"
        fi
      fi
    done
    # access key do IAM user 'raiji-actions' (usada nos secrets do GitHub)
    if [[ -f "$ACTIONS_KEY_FILE" ]]; then
      ACTIONS_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["AccessKey"]["AccessKeyId"])' "$ACTIONS_KEY_FILE" 2>/dev/null || true)"
      ACTIONS_SEC="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["AccessKey"]["SecretAccessKey"])' "$ACTIONS_KEY_FILE" 2>/dev/null || true)"
      if [[ -n "$ACTIONS_ID" && -n "$ACTIONS_SEC" ]]; then
        ok "access key do IAM user 'raiji-actions' carregada de $ACTIONS_KEY_FILE"
      else
        missing_item "$ACTIONS_KEY_FILE existe mas não pôde ser lido como JSON de access key"
        decision "Recriar a access key: aws iam create-access-key --user-name raiji-actions > $ACTIONS_KEY_FILE"
      fi
    else
      missing_item "access key do IAM user 'raiji-actions' não encontrada ($ACTIONS_KEY_FILE)"
      decision "Se o IAM user 'raiji-actions' já tinha access key (criada antes, é exibida uma única vez), crie uma NOVA: aws iam create-access-key --user-name raiji-actions > $ACTIONS_KEY_FILE — ou me envie as credenciais a usar nos secrets AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY."
    fi
  elif [[ "$AWS_CONNECTED" == 1 ]]; then
    info "[dry-run] bootstrap dev/homolog, Elastic IP, chave SSH e access key do 'raiji-actions'"
    BOOTSTRAP_RESULT="dev(ok) homolog(ok) [dry-run]"
  fi
fi

# ========================================================================
# FASE 4 · GITHUB — secrets/variables/environments no GitHub Actions
# ========================================================================
phase 4 "GITHUB — secrets, variables e environments (gh CLI)"
GH_READY=0; REPO=""; BRANCH=""

if ! phase_wanted github; then
  info "fase github pulada por opção (--only/--skip)"
else
  GH_CMD=""
  if [[ -n "$GH_TOKEN_OPT" ]]; then export GH_TOKEN="$GH_TOKEN_OPT"; GH_CMD="gh"; fi
  if command -v gh >/dev/null 2>&1; then GH_CMD="gh"; fi

  REPO="$REPO_OPT"
  if [[ -z "$REPO" ]] && git rev-parse --git-dir >/dev/null 2>&1; then
    URL="$(git remote get-url origin 2>/dev/null || true)"
    REPO="$(echo "$URL" | sed -E 's#.*github\.com[:/]##; s#\.git$##' | grep -E '^[^/]+/[^/]+$' || true)"
  fi
  BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"

  if [[ -z "$GH_CMD" ]]; then
    missing_item "gh (GitHub CLI) não instalado — fase GITHUB pulada (https://cli.github.com)"
    decision "Instalar o GitHub CLI OU enviar um token com ADMIN do repositório (github.com/settings/tokens: classic com scope 'repo', ou fine-grained com Secrets/Variables/Environments: write) para eu criar os secrets."
  elif [[ "$DRY_RUN" == 1 ]]; then
    info "[dry-run] gh autenticado e com admin em ${REPO:-<repositório>}"
    GH_READY=1
  elif ! gh auth status >/dev/null 2>&1; then
    missing_item "gh sem autenticação (export GH_TOKEN=... ou 'gh auth login') — fase GITHUB pulada"
    decision "Enviar/exportar um GH_TOKEN com admin do repositório (ou rodar 'gh auth login' na sua máquina)."
  elif [[ -z "$REPO" ]]; then
    missing_item "repositório GitHub não detectado (sem remote 'origin' e sem --repo owner/name)"
    decision "Informar o repositório: --repo DONO/NOME (ou adicionar o remote origin)."
  elif ! gh api "repos/$REPO" --jq '.permissions.admin' 2>/dev/null | grep -q true; then
    missing_item "gh autenticado, mas SEM admin em $REPO (não consegue criar secrets)"
    decision "Gerar um token de uma conta com admin do repositório $REPO e enviar/exportar como GH_TOKEN."
  else
    ok "gh autenticado com ADMIN em $REPO (branch local: $BRANCH)"
    GH_READY=1
  fi
fi

if [[ "$GH_READY" == 1 ]]; then
  set_gh_secret() { exec_cmd gh secret set "$1" --repo "$REPO" --body "$2"; }

  # 4.1 credenciais AWS para o Actions (IAM user raiji-actions)
  if [[ -n "$ACTIONS_ID" && -n "$ACTIONS_SEC" ]]; then
    set_gh_secret AWS_ACCESS_KEY_ID "$ACTIONS_ID" \
      && done_item "GitHub secret AWS_ACCESS_KEY_ID (IAM user raiji-actions)" \
      || missing_item "falhou ao criar secret AWS_ACCESS_KEY_ID"
    set_gh_secret AWS_SECRET_ACCESS_KEY "$ACTIONS_SEC" \
      && done_item "GitHub secret AWS_SECRET_ACCESS_KEY (IAM user raiji-actions)" \
      || missing_item "falhou ao criar secret AWS_SECRET_ACCESS_KEY"
  else
    missing_item "secrets AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY NÃO criados (sem access key do IAM user raiji-actions)"
    decision "Enviar as credenciais do IAM user 'raiji-actions' (AccessKeyId/SecretAccessKey) para eu criar os secrets, OU criar manualmente em Settings -> Secrets and variables -> Actions -> Secrets."
  fi

  # 4.2 por ambiente: IP (variable), SSH/DB/Keycloak/smoke (secrets)
  for env in $ENVS; do
    SUF="$(echo "$env" | tr '[:lower:]' '[:upper:]')"
    IP_VAR="EC2_IP_$SUF"; PEM_VAR="PEM_$SUF"
    IP_VAL="${!IP_VAR:-}"; PEM_VAL="${!PEM_VAR:-}"

    # senha do banco: gerada uma vez por execução e salva localmente
    if [[ "$env" == "dev" ]]; then DBP="$DB_PASS_DEV"; else DBP="$DB_PASS_HOMOLOG"; fi
    if [[ -z "$DBP" ]]; then
      DBP="$(gen_password)"
      save_secret_line "DB_PASSWORD_$SUF=$DBP"
      info "DB_PASSWORD_$SUF gerado e salvo em $SECRETS_FILE"
    fi

    if [[ -n "$IP_VAL" ]]; then
      exec_cmd gh variable set "AWS_EC2_HOST_$SUF" --repo "$REPO" --body "$IP_VAL" \
        && done_item "GitHub variable AWS_EC2_HOST_$SUF = $IP_VAL" \
        || missing_item "falhou ao criar variable AWS_EC2_HOST_$SUF"
    else
      missing_item "variable AWS_EC2_HOST_$SUF NÃO criada (sem IP do bootstrap)"
    fi

    if [[ -n "$PEM_VAL" && -f "$PEM_VAL" ]]; then
      exec_cmd gh secret set "AWS_SSH_KEY_$SUF" --repo "$REPO" --body "$(cat "$PEM_VAL")" \
        && done_item "GitHub secret AWS_SSH_KEY_$SUF (conteúdo da .pem)" \
        || missing_item "falhou ao criar secret AWS_SSH_KEY_$SUF"
    else
      missing_item "secret AWS_SSH_KEY_$SUF NÃO criado (sem .pem local)"
    fi
    set_gh_secret "DB_PASSWORD_$SUF" "$DBP" \
      && done_item "GitHub secret DB_PASSWORD_$SUF" \
      || missing_item "falhou ao criar secret DB_PASSWORD_$SUF"
    set_gh_secret "KEYCLOAK_ADMIN_CLIENT_SECRET_$SUF" "$KC_SECRET" \
      && done_item "GitHub secret KEYCLOAK_ADMIN_CLIENT_SECRET_$SUF" \
      || missing_item "falhou ao criar secret KEYCLOAK_ADMIN_CLIENT_SECRET_$SUF"
    set_gh_secret "SMOKE_USERNAME_$SUF" "$SMOKE_USER" \
      && done_item "GitHub secret SMOKE_USERNAME_$SUF (=$SMOKE_USER)" \
      || missing_item "falhou ao criar secret SMOKE_USERNAME_$SUF"
    set_gh_secret "SMOKE_PASSWORD_$SUF" "$SMOKE_PASS" \
      && done_item "GitHub secret SMOKE_PASSWORD_$SUF" \
      || missing_item "falhou ao criar secret SMOKE_PASSWORD_$SUF"

    exec_cmd gh api -X PUT "repos/$REPO/environments/$env" >/dev/null \
      && info "environment '$env' garantido (aprovação manual é opcional e não bloqueia o CD)" \
      || warn "não consegui criar o environment '$env' (opcional — não bloqueia)"
  done

  # 4.3 funcionais opcionais (se o env estiver definido, vira secret)
  for s in "${OPTIONAL_SECRETS[@]}"; do
    VAL="${!s:-}"
    if [[ -n "$VAL" ]]; then
      set_gh_secret "$s" "$VAL" \
        && done_item "GitHub secret $s (funcional ligado)" \
        || missing_item "falhou ao criar secret $s"
    else
      warn "$s não definido — recurso fica DESLIGADO (CNH/e-mail/Stripe/CORS)"
    fi
  done
fi

# ========================================================================
# FASE 5 · PUSH — commita as correções aplicadas, publica e dispara o CD
# ========================================================================
phase 5 "PUSH — commit, publicar código e disparar o CD"
PUSH_DONE=0
DEVELOP_PUSHED=""
BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')}"

if ! phase_wanted push; then
  info "fase push pulada por opção (--only/--skip)"
elif ! git rev-parse --git-dir >/dev/null 2>&1; then
  missing_item "a raiz do projeto não é um repositório git (git init + remote necessário)"
  decision "git init && git remote add origin git@github.com:DONO/NOME.git"
else
  # 5.1 commit das correções implementadas pela fase APPLY (independe de remote)
  if [[ "${#APPLY_LIST[@]}" -gt 0 && "$DRY_RUN" == 0 ]]; then
    ADD_PATHS=()
    for rel in "${APPLY_LIST[@]}"; do [[ -e "$ROOT/$rel" ]] && ADD_PATHS+=("$rel"); done
    # o próprio script também entra no commit (o projeto mantém a ferramenta)
    [[ -f "$ROOT/cd-setup-all.sh" ]] && ADD_PATHS+=("cd-setup-all.sh")
    if git add -- "${ADD_PATHS[@]}" 2>/dev/null && ! git diff --cached --quiet; then
      if git commit -m "ci(cd): aplica correções do CD via cd-setup-all.sh (workflows, deploy AWS, compose, bootstrap)" >/dev/null 2>&1; then
        done_item "commit das correções aplicadas criado na branch '$BRANCH'"
      elif git -c user.name="cd-setup-all" -c user.email="cd@raiji.local" commit -m "ci(cd): aplica correções do CD via cd-setup-all.sh (workflows, deploy AWS, compose, bootstrap)" >/dev/null 2>&1; then
        done_item "commit das correções aplicadas criado (identidade git padrão do script)"
        warn "git sem user.name/user.email configurado — configure: git config --global user.name 'Você'"
      else
        missing_item "git commit falhou (arquivos aplicados ficam staged)"
        decision "Rodar manualmente: git commit -m 'ci(cd): aplica correções do CD'"
      fi
    else
      info "nada a commitar (correções já estavam commitadas)"
    fi
  fi

  if git remote get-url origin >/dev/null 2>&1; then
  info "remote origin: $(git remote get-url origin)"
  if [[ ! "$BRANCH" =~ ^(fix/cd-deploy-hardening|develop)$ && "$BRANCH" != "?" ]]; then
    warn "branch atual é '$BRANCH' — o CD espera o conteúdo em develop (DEV) e main (HOMOLOG)"
  fi

  # 5.2 publicar a branch atual (backup das correções)
  if exec_cmd git push -u origin "$BRANCH"; then
    done_item "branch '$BRANCH' publicada no origin"
    PUSH_DONE=1
  else
    missing_item "git push da branch '$BRANCH' falhou (sem credencial git? push bloqueado por proteção?)"
    decision "Enviar acesso de push (SSH/token) ou rodar manualmente: git push -u origin $BRANCH"
  fi

  # 5.3 integrar em develop (dispara Deploy DEV) — NUNCA automático:
  # só com --push-develop E confirmação explícita (ou --yes)
  if [[ "$PUSH_DEVELOP" == 1 && "$DRY_RUN" == 0 && "$ASSUME_YES" != 1 ]]; then
    RESP=""
    if ! read -r -t 300 -p "  » AVISO: isso publica develop e DISPARA o Deploy DEV. Enter=CONFIRMA / qualquer texto+Enter=CANCELA " RESP; then
      RESP="cancelado"
    fi
    if [[ -n "$RESP" ]]; then
      info "--push-develop cancelado — NADA foi publicado na develop (para pular a pergunta: --yes)"
      PUSH_DEVELOP=0
    fi
  fi
  if [[ "$PUSH_DEVELOP" == 1 ]]; then
    if git show-ref --verify --quiet refs/heads/develop; then
      if exec_cmd git checkout develop; then
        if exec_cmd git merge --no-edit "$BRANCH"; then
          if exec_cmd git push origin develop; then
            done_item "develop atualizada com '$BRANCH' e publicada -> Deploy DEV disparado"
            DEVELOP_PUSHED=1
          else
            missing_item "push de develop falhou"
          fi
        else
          missing_item "merge de '$BRANCH' em develop teve CONFLITO"
          exec_cmd git merge --abort 2>/dev/null || true
          decision "Resolver conflitos e rodar: git checkout develop && git merge $BRANCH && git push origin develop — ou abrir um PR."
        fi
      else
        missing_item "git checkout develop falhou (working tree suja?)"
        decision "Commit/stash das mudanças locais antes do merge."
      fi
      exec_cmd git checkout "$BRANCH" 2>/dev/null || true
    else
      missing_item "branch 'develop' não existe neste clone (não consegui integrar)"
      decision "git fetch origin && git checkout develop && git merge $BRANCH && git push origin develop"
    fi
  fi

  # 5.4 sincronizar main (registra workflows no default + dispara HOMOLOG)
  if [[ "$SYNC_MAIN" == 1 ]]; then
    if git show-ref --verify --quiet refs/heads/develop; then
      if exec_cmd git push origin develop:main; then
        done_item "main sincronizada (develop:main) -> workflows registrados no branch default + Deploy HOMOLOG disparado"
      else
        missing_item "push develop:main falhou (main divergida?)"
        decision "Se main divergiu: git fetch origin && git checkout main && git merge --ff-only origin/develop (ou decidir resetar main a partir de develop)."
      fi
    else
      missing_item "--sync-main exige develop local (não encontrada)"
    fi
  fi
  else
    missing_item "sem remote 'origin' neste clone — correções commitadas localmente, mas nada publicado"
    decision "Adicionar o remote e publicar: git remote add origin git@github.com:DONO/NOME.git && git push -u origin $BRANCH"
  fi
fi

if [[ "$WATCH" == 1 && "$DRY_RUN" == 0 && "$GH_READY" == 1 && -n "$DEVELOP_PUSHED" ]]; then
  step "acompanhando o run do Deploy DEV (1º deploy pode levar 15-30 min)..."
  RUN_ID="$(gh run list --repo "$REPO" --workflow deploy-dev.yml --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
  if [[ -n "$RUN_ID" ]]; then
    if timeout 2700 gh run watch "$RUN_ID" --repo "$REPO" --exit-status >/dev/null 2>&1; then
      done_item "Deploy DEV concluído com SUCESSO (run $RUN_ID)"
    else
      missing_item "Deploy DEV (run $RUN_ID) terminou com falha — logs: gh run view $RUN_ID --repo $REPO --log-failed"
      decision "Falhou no SSH/EC2? Rode o bootstrap de novo. Falhou no smoke? Confira KEYCLOAK_ADMIN_CLIENT_SECRET (client raiji-admin) e o usuário teste/teste123."
    fi
  else
    warn "nenhum run de Deploy DEV encontrado para acompanhar"
  fi
elif [[ "$WATCH" == 1 ]]; then
  warn "--watch ignorado (precisa de --push-develop concluído + gh autenticado)"
fi

# ========================================================================
# FASE 6 · REPORT — o que foi feito e O QUE VOCÊ PRECISA FAZER/ENVIAR
# ========================================================================
phase 6 "REPORT — resumo e próximos passos"
printf '\n  %-24s %s\n' "Correções aplicadas:" "$(if [[ "${APPLIED:-0}" -gt 0 ]]; then echo "$APPLIED arquivo(s) implementados"; elif [[ "${UNCHANGED:-0}" -gt 0 ]]; then echo "já estavam corretas ($UNCHANGED)"; else echo "não executada"; fi)"
printf '  %-24s %s\n' "AWS conectada:" "$(if [[ "${AWS_CONNECTED:-0}" == 1 && "${DRY_RUN:-0}" == 0 ]]; then echo SIM; elif [[ "${DRY_RUN:-0}" == 1 ]]; then echo "dry-run"; else echo 'NÃO (sem credenciais)'; fi)"
printf '  %-24s %s\n' "Bootstrap:" "${BOOTSTRAP_RESULT:-não executado}"
printf '  %-24s %s\n' "GitHub admin (gh):" "$(if [[ "${GH_READY:-0}" == 1 ]]; then echo SIM; else echo 'NÃO (sem token/admin)'; fi)"
printf '  %-24s %s\n' "Branch publicada:" "$(if [[ "${PUSH_DONE:-0}" == 1 ]]; then echo SIM; else echo 'NÃO'; fi)"
printf '  %-24s %s\n' "Deploy DEV disparado:" "$(if [[ -n "${DEVELOP_PUSHED:-}" ]]; then echo SIM; else echo 'não (padrão: nada vai para develop)'; fi)"
printf '  %-24s %s\n' "Segredos locais:" "$SECRETS_FILE"

if [[ ${#DONE_LIST[@]} -gt 0 ]]; then
  printf '\n%sFEITO NESTA EXECUÇÃO%s\n' "$C_OK" "$C_NC"
  for d in "${DONE_LIST[@]}"; do printf '  ✔ %s\n' "$d"; done
fi

printf '\n%s━━━ O QUE VOCÊ PRECISA FAZER OU ENVIAR ━━━%s\n' "$C_WARN" "$C_NC"
if [[ ${#MISSING_LIST[@]} -gt 0 ]]; then
  printf '\n%sPendências que impedem ou limitam o CD:%s\n' "$C_BAD" "$C_NC"
  for m in "${MISSING_LIST[@]}"; do printf '  ✘ %s\n' "$m"; done
fi
if [[ ${#DECISIONS_LIST[@]} -gt 0 ]]; then
  printf '\n%sAções suas (em ordem de prioridade):%s\n' "$C_INFO" "$C_NC"
  i=1
  for d in "${DECISIONS_LIST[@]}"; do printf '  %2d) %s\n' "$i" "$d"; i=$((i+1)); done
fi

cat <<'LEMBRETES'

LEMBRETES FINAIS
  1) Este script NÃO publica develop/main por padrão — atua só na branch
     atual (ex.: fix/cd-deploy-hardening). Quando decidir publicar:
     ./cd-setup-all.sh --push-develop --watch  (pede confirmação; --yes pula).
     Push em develop dispara Deploy DEV; push em main dispara HOMOLOG.
     Rollback/Guardian só ficam VISÍVEIS no GitHub quando main (branch
     default) receber os workflows (--sync-main).
  2) Guardian vigia a cada 10 min e faz rollback automático — funciona só
     depois de existir uma versão anterior em execução.
  3) KEYCLOAK_ADMIN_CLIENT_SECRET usa o padrão 'raiji-admin-secret', que é
     EXATAMENTE o segredo do client raiji-admin no realm importado
     (keycloak/raiji-realm.json). Mudou um lado? Mude o outro.
  4) Smoke usa o usuário teste/teste123, que já existe no realm importado.
  5) Custo estimado: 2 × t3.small + 2 Elastic IPs ≈ US$ 30–35/mês. Só dev?
     Rode com --envs dev (e reutilize o IP em AWS_EC2_HOST_HOMOLOG).
  6) O ambiente sobe em HTTP por IP (ex.: http://IP:8000). Domínio + HTTPS
     é o próximo passo (não bloqueia o CD).
  7) Rollback manual: gh workflow run rollback.yml -f environment=dev
  8) Backups dos arquivos alterados ficam em .cd-secrets-local/backup-*/
     (gitignored). Para voltar um arquivo: cp backup/... caminho/original.
LEMBRETES

if [[ ${#MISSING_LIST[@]} -eq 0 ]]; then
  printf '%sTUDO PRONTO — CD implementado e configurado de ponta a ponta. ✅%s\n' "$C_OK" "$C_NC"
  exit 0
fi
printf '%sFaltam %d itens (listados acima). Resolva-os e rode o script de novo.%s\n' "$C_WARN" "${#MISSING_LIST[@]}" "$C_NC"
exit 1
