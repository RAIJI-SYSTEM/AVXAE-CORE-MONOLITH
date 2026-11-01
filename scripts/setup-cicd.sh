#!/usr/bin/env bash
# =============================================================================
# setup-cicd.sh — S1-03 · Pipeline de CI/CD e deploy experimental de homologação
# =============================================================================
# Script ÚNICO e autocontido que implementa toda a issue no projeto:
#
#   * .github/workflows/ci.yml            -> CI (PR + push main/develop), matriz
#                                            dinâmica de módulos em paralelo,
#                                            mvn clean verify + JaCoCo + Docker
#   * .github/workflows/deploy.yml        -> deploy reutilizável (AWS: ECR + EC2)
#   * .github/workflows/deploy-dev.yml    -> push em `develop`  => ambiente dev
#   * .github/workflows/deploy-homolog.yml-> push em `main`     => ambiente homolog (sistema)
#   * .github/workflows/rollback.yml      -> rollback manual (workflow_dispatch)
#   * scripts/smoke-test.sh               -> 4 checagens (health/realm/token/me),
#                                            modo serial e PARALELO
#   * scripts/aws/{deploy,deploy-remote,rollback,ecr-retag,bootstrap-aws}.sh
#   * docker-compose.aws.yml              -> override p/ nuvem (imagens ECR, envs)
#   * docs/DEPLOY.md                      -> URL, variáveis, rollback, limitações
#   * tests/cicd/*                        -> suíte que VERIFICA se tudo foi feito
#
# Uso:
#   bash scripts/setup-cicd.sh           # gera/sobrescreve todos os arquivos
#   bash scripts/setup-cicd.sh --check   # gera e roda a suíte de testes
#
# É idempotente: pode rodar quantas vezes quiser.
# =============================================================================
set -euo pipefail

MODE="apply"
if [[ "${1:-}" == "--check" ]]; then MODE="check"; fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
log()  { printf '%s[setup-cicd]%s %s\n' "$GRN" "$NC" "$*"; }
warn() { printf '%s[setup-cicd]%s %s\n' "$YLW" "$NC" "$*"; }

write_file() { # write_file <path> <heredoc-stdin>
  mkdir -p "$(dirname "$1")"
  cat > "$1"
  log "gravado: $1"
}

# =============================================================================
# 1) WORKFLOW: CI  (checkout -> JDK 21 -> cache Maven -> mvn clean verify -> JaCoCo)
#    + trilhas paralelas: matriz dinâmica de testes unitários por módulo e build Docker
# =============================================================================
write_file .github/workflows/ci.yml <<'@@CI_YML@@'
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
@@CI_YML@@

# =============================================================================
# 2) WORKFLOW REUTILIZÁVEL DE DEPLOY (AWS: ECR + EC2 + docker compose)
# =============================================================================
write_file .github/workflows/deploy.yml <<'@@DEPLOY_YML@@'
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
    environment: ${{ inputs.environment }}
    outputs:
      registry: ${{ steps.ecr.outputs.registry }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

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
    environment: ${{ inputs.environment }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

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
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY }}
          AWS_REGION_NAME: ${{ needs.config.outputs.aws-region }}
          BASE_PORT: ${{ needs.config.outputs.base-port }}
          KC_PORT: ${{ needs.config.outputs.kc-port }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
          KEYCLOAK_ADMIN_CLIENT_SECRET: ${{ secrets.KEYCLOAK_ADMIN_CLIENT_SECRET }}
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
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD || 'teste123' }}
        run: ./scripts/smoke-test.sh --check "${{ matrix.check }}" --retries 18 --retry-delay 10

  # ---------------------------------------------------------------------------
  # Release gate: só roda com TUDO verde; publica a URL acessível no sumário.
  # ---------------------------------------------------------------------------
  release:
    name: Release (${{ inputs.environment }}) ✅
    needs: [build-push, deploy, smoke, config]
    if: success()
    runs-on: ubuntu-latest
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
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY }}
          AWS_REGION_NAME: ${{ needs.config.outputs.aws-region }}
          BASE_PORT: ${{ needs.config.outputs.base-port }}
          BASE_URL: ${{ env.BASE_URL }}
          SMOKE_USERNAME: ${{ secrets.SMOKE_USERNAME || 'teste' }}
          SMOKE_PASSWORD: ${{ secrets.SMOKE_PASSWORD || 'teste123' }}
        run: ./scripts/aws/rollback.sh --auto
@@DEPLOY_YML@@

# =============================================================================
# 3) WORKFLOW: deploy DEV (branch develop -> ambiente dev)
# =============================================================================
write_file .github/workflows/deploy-dev.yml <<'@@DEPLOY_DEV_YML@@'
# =============================================================================
# CD DEV — push na branch `develop` atualiza o ambiente de DESENVOLVIMENTO (dev)
# na AWS. Região/portas definidos em infra/aws/aws-config.env.
# Segredos/variáveis: ver docs/DEPLOY.md (Settings -> Environments -> dev).
# =============================================================================
name: Deploy DEV (develop -> AWS dev)

on:
  push:
    branches: [develop]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  deploy-dev:
    name: dev
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
@@DEPLOY_DEV_YML@@

# =============================================================================
# 4) WORKFLOW: deploy HOMOLOG/SISTEMA (branch main -> ambiente do sistema)
# =============================================================================
write_file .github/workflows/deploy-homolog.yml <<'@@DEPLOY_HOMOLOG_YML@@'
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
  deploy-homolog:
    name: homolog
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
@@DEPLOY_HOMOLOG_YML@@

# =============================================================================
# 5) WORKFLOW: rollback manual (atende "testado manualmente ao menos uma vez")
# =============================================================================
write_file .github/workflows/rollback.yml <<'@@ROLLBACK_YML@@'
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
          SSH_PRIVATE_KEY: ${{ inputs.environment == 'dev' && secrets.AWS_SSH_KEY_DEV || secrets.AWS_SSH_KEY_HOMOLOG }}
          SMOKE_USERNAME: ${{ inputs.environment == 'dev' && secrets.SMOKE_USERNAME_DEV || secrets.SMOKE_USERNAME_HOMOLOG }}
          SMOKE_PASSWORD: ${{ inputs.environment == 'dev' && secrets.SMOKE_PASSWORD_DEV || secrets.SMOKE_PASSWORD_HOMOLOG }}
        run: ./scripts/aws/rollback.sh
@@ROLLBACK_YML@@

# =============================================================================
# 6) docker-compose.aws.yml — override de nuvem (imagem ECR + variáveis)
# =============================================================================
write_file docker-compose.aws.yml <<'@@COMPOSE_AWS@@'
# =============================================================================
# Override de deploy na AWS (EC2). Uso (na instância):
#   docker compose -p raiji-<env> -f docker-compose.yml -f docker-compose.aws.yml up -d
#
# Variáveis (arquivo .env gerado pelo scripts/aws/deploy.sh — NUNCA commitar):
#   ECR_REGISTRY, ECR_REPOSITORY, IMAGE_TAG, BASE_PORT, KC_PORT,
#   DB_URL, DB_USER, DB_PASSWORD, JWK_SET_URI, KEYCLOAK_ADMIN_CLIENT_SECRET,
#   SPRING_PROFILES_ACTIVE, POSTGRES_DB
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
    ports: !override []          # app acessível apenas via Kong

  kong:
    container_name: ${COMPOSE_PROJECT_NAME:-raiji}-kong
    ports: !override
      - "${BASE_PORT:-8000}:8000"   # única entrada pública do ambiente
@@COMPOSE_AWS@@

# =============================================================================
# 7) scripts/smoke-test.sh — as 4 checagens da issue (serial e paralelo)
# =============================================================================
write_file scripts/smoke-test.sh <<'@@SMOKE_SH@@'
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
@@SMOKE_SH@@

# =============================================================================
# 8) scripts/aws/ecr-retag.sh — retag de imagem no ECR sem re-push (rollback)
# =============================================================================
write_file scripts/aws/ecr-retag.sh <<'@@ECR_RETAG_SH@@'
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
@@ECR_RETAG_SH@@

# =============================================================================
# 9) scripts/aws/deploy.sh — orquestração do deploy (roda no GitHub Runner)
# =============================================================================
write_file scripts/aws/deploy.sh <<'@@DEPLOY_SH@@'
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
log "ECR registry: $ECR_REGISTRY | tag: $IMAGE_TAG"

# --- 3) Sincronizar arquivos de infra -----------------------------------------
log "sincronizando arquivos para $SSH_TARGET:$REMOTE_DIR ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p '$REMOTE_DIR/scripts/aws'"
tar czf - \
  docker-compose.yml docker-compose.aws.yml \
  kong keycloak postgres \
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
KC_PORT=$KC_PORT
SPRING_PROFILES_ACTIVE=docker
DB_URL=jdbc:postgresql://postgres:5432/raiji_app
DB_USER=raiji
DB_PASSWORD='$DB_PASSWORD'
POSTGRES_DB=raiji_keycloak
JWK_SET_URI=http://keycloak:8080/realms/raiji/protocol/openid-connect/certs
KEYCLOAK_ADMIN_CLIENT_SECRET='$KEYCLOAK_ADMIN_CLIENT_SECRET'
AWS_REGION=$AWS_REGION_NAME
ENV_EOF

# --- 5) Deploy remoto ----------------------------------------------------------
log "executando deploy-remote.sh na EC2 ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
  "cd '$REMOTE_DIR' && chmod +x scripts/aws/deploy-remote.sh scripts/smoke-test.sh && ./scripts/aws/deploy-remote.sh" \
  || die "deploy remoto falhou (health-gate). Se havia versão anterior, o self-rollback já foi tentado; veja logs acima."

log "deploy concluído com sucesso. URL: http://$EC2_HOST:$BASE_PORT"
@@DEPLOY_SH@@

# =============================================================================
# 10) scripts/aws/deploy-remote.sh — roda NA instância EC2
# =============================================================================
write_file scripts/aws/deploy-remote.sh <<'@@DEPLOY_REMOTE_SH@@'
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
"${COMPOSE[@]}" up -d

# grava estado atual
grep -v '^IMAGE_TAG=' "$STATE_FILE" 2>/dev/null > "$STATE_FILE.tmp" || true
echo "IMAGE_TAG=$IMAGE_TAG" >> "$STATE_FILE.tmp"
echo "DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$STATE_FILE.tmp"
mv "$STATE_FILE.tmp" "$STATE_FILE"

# --- health-gate (reusa o smoke-test) --------------------------------------------
log "aguardando health-gate em http://localhost:$BASE_PORT ..."
if BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries 36 --retry-delay 10; then
  log "health OK — deploy concluído."
  exit 0
fi

# --- self-rollback local ----------------------------------------------------------
echo "[remote] ERRO: health-gate falhou." >&2
if [[ -n "$PREVIOUS_TAG" ]]; then
  echo "[remote] self-rollback para $PREVIOUS_TAG ..." >&2
  IMAGE_TAG="$PREVIOUS_TAG" "${COMPOSE[@]}" up -d --no-deps app || true
  cp ".deploy-state.previous" "$STATE_FILE" 2>/dev/null || true
else
  # Sem versão anterior: NÃO servir versão quebrada — o app fica parado até
  # que um deploy correto seja publicado ("não deixe subir até subir certo").
  echo "[remote] SEM versão anterior — parando o app para não servir versão quebrada." >&2
  "${COMPOSE[@]}" stop app >/dev/null 2>&1 || true
fi
exit 1
@@DEPLOY_REMOTE_SH@@

# =============================================================================
# 11) scripts/aws/rollback.sh — retag da imagem anterior + redeploy + smoke
# =============================================================================
write_file scripts/aws/rollback.sh <<'@@ROLLBACK_SH@@'
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
IMAGE_TAG="${ENVIRONMENT}-current" "${COMPOSE[@]}" up -d --no-deps app
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${ENVIRONMENT}-current/" .deploy-state 2>/dev/null || true
BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries 36 --retry-delay 10
REMOTE_EOF

# --- 3) SMOKE completo (paralelo) ----------------------------------------------------
log "smoke test completo em $BASE_URL ..."
SMOKE_USERNAME="${SMOKE_USERNAME:-teste}" SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}" \
BASE_URL="$BASE_URL" "$SCRIPT_DIR/../smoke-test.sh" --parallel --retries 6 --retry-delay 10 \
  || die "smoke pós-rollback falhou — intervenção manual necessária (veja docs/DEPLOY.md)"

log "rollback concluído com sucesso. Ambiente restaurado: $BASE_URL"
[[ "$AUTO" == "--auto" ]] && log "(executado automaticamente)"
exit 0
@@ROLLBACK_SH@@

# =============================================================================
# 12) scripts/aws/bootstrap-aws.sh — infra inicial (ECR + EC2) via AWS CLI
# =============================================================================
write_file scripts/aws/bootstrap-aws.sh <<'@@BOOTSTRAP_SH@@'
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
log() { echo "[bootstrap:$ENVIRONMENT] $*"; }

command -v aws >/dev/null || { echo "aws cli não encontrado" >&2; exit 1; }
log "configuração: região=$REGION tipo=$INSTANCE_TYPE portas=$BASE_PORT/$KC_PORT"

# --- ECR (idempotente) ----------------------------------------------------------
if ! aws ecr describe-repositories --region "$REGION" --repository-names "$REPO" >/dev/null 2>&1; then
  log "criando repositório ECR $REPO ..."
  aws ecr create-repository --region "$REGION" --repository-name "$REPO" \
    --image-scanning-configuration scanOnPush=true >/dev/null
else
  log "ECR $REPO já existe."
fi

# --- IAM role para a EC2 puxar imagens ------------------------------------------
if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  log "criando IAM role $ROLE ..."
  aws iam create-role --role-name "$ROLE" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
  aws iam put-role-policy --role-name "$ROLE" --policy-name ecr-pull --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Action\": [\"ecr:GetAuthorizationToken\"],
      \"Resource\": \"*\"
    }, {
      \"Effect\": \"Allow\",
      \"Action\": [\"ecr:BatchCheckLayerAvailability\",\"ecr:GetDownloadUrlForLayer\",\"ecr:BatchGetImage\"],
      \"Resource\": \"arn:aws:ecr:${REGION}:*:repository/${REPO}\"
    }]
  }"
  aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
  sleep 10
else
  log "IAM role $ROLE já existe."
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

  IP="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
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
  IP="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
fi

SUFIXO="$SUFFIX"
cat <<FIM

============================================================
 Bootstrap $ENVIRONMENT concluído!
============================================================
 Config em uso: $CONFIG_FILE (edite-o para mudar tipo de
 máquina/região/portas e rerode este script).

 URL do ambiente: http://$IP:$BASE_PORT

 Configure no GitHub (Settings -> Secrets and variables -> Actions):

 [Variables] (aba Variables)
   AWS_EC2_HOST_$SUFIXO = $IP

 [Secrets] (aba Secrets — repositório)
   AWS_ACCESS_KEY_ID               = <IAM user p/ Actions>
   AWS_SECRET_ACCESS_KEY           = <IAM user p/ Actions>

 [Secrets] (Environment '$ENVIRONMENT' — Settings -> Environments)
   AWS_SSH_KEY_$SUFIXO             = conteúdo de $KEY_FILE
   DB_PASSWORD_$SUFIXO             = <senha forte do banco>
   KEYCLOAK_ADMIN_CLIENT_SECRET_$SUFIXO = <segredo do client raiji-admin>
   SMOKE_USERNAME_$SUFIXO          = teste            (opcional)
   SMOKE_PASSWORD_$SUFIXO          = <senha do usuário de smoke> (opcional)

 A instância nova precisa de ~2-5 min para instalar Docker (user-data).
 Teste: ssh -i $KEY_FILE ubuntu@$IP 'docker --version'
============================================================
FIM
@@BOOTSTRAP_SH@@

# =============================================================================
# 12b) infra/aws/aws-config.env — fonte única da configuração AWS (fácil mudar)
# =============================================================================
write_file infra/aws/aws-config.env <<'@@AWS_CONFIG@@'
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
@@AWS_CONFIG@@

# =============================================================================
# 12c) WORKFLOW + SCRIPT: Guardian — smoke contínuo, rollback automático e
#      Issue de alerta (proteção contra erros inesperados pós-deploy)
# =============================================================================
write_file .github/workflows/guardian.yml <<'@@GUARDIAN_YML@@'
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
@@GUARDIAN_YML@@

write_file scripts/aws/guardian.sh <<'@@GUARDIAN_SH@@'
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
@@GUARDIAN_SH@@

# =============================================================================
# 13) tests/cicd — suíte que verifica se TUDO da issue foi implementado
# =============================================================================
write_file tests/cicd/run-all.sh <<'@@RUNALL_SH@@'
#!/usr/bin/env bash
# =============================================================================
# run-all.sh — suíte de verificação da S1-03.
#   1. bash -n (sintaxe) em todos os scripts
#   2. actionlint nos workflows (se disponível; senão, tenta baixar; senão, avisa)
#   3. testes estruturais (python3 + unittest) — YAML, gatilhos, etapas, docs
#   4. teste funcional do smoke-test.sh contra um servidor mock (passa E falha)
#   5. idempotência do setup-cicd.sh (rodar 2x não muda nada)
# Uso: tests/cicd/run-all.sh [--quick]
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
QUICK="${1:-}"
FAILURES=0

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
step() { printf '\n%s==>%s %s\n' "$YLW" "$NC" "$*"; }
ok()   { printf '%s OK %s %s\n' "$GRN" "$NC" "$*"; }
ko()   { printf '%sFAIL%s %s\n' "$RED" "$NC" "$*"; FAILURES=$((FAILURES+1)); }

# --- PYTHONPATH p/ PyYAML (usa .tools se o sistema não tiver) --------------------
export PYTHONPATH="${PYTHONPATH:-}"
if ! python3 -c 'import yaml' 2>/dev/null; then
  for cand in "$ROOT/.tools/pylibs" "$ROOT/../.tools/pylibs"; do
    if PYTHONPATH="$cand" python3 -c 'import yaml' 2>/dev/null; then
      export PYTHONPATH="$cand${PYTHONPATH:+:$PYTHONPATH}"
      break
    fi
  done
fi

# --- 1) sintaxe dos scripts ------------------------------------------------------
step "1/5 · Sintaxe bash (bash -n)"
while IFS= read -r f; do
  if bash -n "$f" 2>/dev/null; then ok "$f"; else ko "$f (sintaxe inválida)"; bash -n "$f"; fi
done < <(find scripts tests -name '*.sh' -type f | sort)

# --- 2) actionlint ----------------------------------------------------------------
step "2/5 · actionlint (workflows)"
ACTIONLINT=""
for cand in "$(command -v actionlint || true)" "$ROOT/.tools/actionlint" "$ROOT/../.tools/actionlint"; do
  [[ -n "$cand" && -x "$cand" ]] && { ACTIONLINT="$cand"; break; }
done
if [[ -z "$ACTIONLINT" ]]; then
  mkdir -p "$ROOT/.tools"
  if curl -fsSL -o "$ROOT/.tools/actionlint.tgz" \
      https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz 2>/dev/null; then
    tar xzf "$ROOT/.tools/actionlint.tgz" -C "$ROOT/.tools" actionlint && chmod +x "$ROOT/.tools/actionlint"
    ACTIONLINT="$ROOT/.tools/actionlint"
  fi
fi
if [[ -n "$ACTIONLINT" ]]; then
  if "$ACTIONLINT" -no-color .github/workflows/*.yml; then
    ok "actionlint: workflows válidos"
  else
    ko "actionlint apontou problemas nos workflows"
  fi
else
  printf '%sWARN%s actionlint indisponível (sem rede?) — etapa pulada\n' "$YLW" "$NC"
fi

# --- 3) testes estruturais ----------------------------------------------------------
step "3/5 · Testes estruturais (python unittest)"
if python3 -m unittest discover -s tests/cicd -p 'test_*.py' -v 2>&1 | tail -n 25; then
  if python3 -m unittest discover -s tests/cicd -p 'test_*.py' >/dev/null 2>&1; then
    ok "testes estruturais passaram"
  else
    ko "testes estruturais falharam"
  fi
else
  ko "testes estruturais falharam (execução)"
fi

# --- 4) teste funcional do smoke-test ------------------------------------------------
step "4/5 · Teste funcional do smoke-test.sh (mock server)"
if [[ "$QUICK" == "--quick" ]]; then
  printf '%sWARN%s --quick: pulando teste funcional\n' "$YLW" "$NC"
elif bash tests/cicd/test_smoke_functional.sh; then
  ok "smoke-test.sh se comporta corretamente (cenários positivos e negativos)"
else
  ko "teste funcional do smoke-test.sh falhou"
fi

# --- 5) idempotência do setup ----------------------------------------------------------
step "5/5 · Idempotência do setup-cicd.sh"
SNAP_BEFORE="$(mktemp)"; SNAP_AFTER="$(mktemp)"
find .github scripts tests docs infra docker-compose.aws.yml -type f \( -name '*.yml' -o -name '*.sh' -o -name '*.py' -o -name '*.md' -o -name '*.env' \) \
  -exec sha256sum {} + | sort > "$SNAP_BEFORE"
bash scripts/setup-cicd.sh >/dev/null 2>&1
find .github scripts tests docs infra docker-compose.aws.yml -type f \( -name '*.yml' -o -name '*.sh' -o -name '*.py' -o -name '*.md' -o -name '*.env' \) \
  -exec sha256sum {} + | sort > "$SNAP_AFTER"
if diff -q "$SNAP_BEFORE" "$SNAP_AFTER" >/dev/null; then
  ok "rodar setup-cicd.sh duas vezes não altera nada (idempotente)"
else
  ko "setup-cicd.sh NÃO é idempotente"; diff "$SNAP_BEFORE" "$SNAP_AFTER" | head -20
fi
rm -f "$SNAP_BEFORE" "$SNAP_AFTER"

# --- resumo ------------------------------------------------------------------------
echo
if [[ "$FAILURES" -eq 0 ]]; then
  printf '%s============================================%s\n' "$GRN" "$NC"
  printf '%s TODOS OS TESTES DA S1-03 PASSARAM ✅%s\n' "$GRN" "$NC"
  printf '%s============================================%s\n' "$GRN" "$NC"
  exit 0
fi
printf '%s============================================%s\n' "$RED" "$NC"
printf '%s %d etapa(s) falharam ❌%s\n' "$RED" "$FAILURES" "$NC"
printf '%s============================================%s\n' "$RED" "$NC"
exit 1
@@RUNALL_SH@@

write_file tests/cicd/mock_server.py <<'@@MOCK_PY@@'
"""Mock do stack (Kong + App + Keycloak) para testar o smoke-test.sh localmente.

Estado controlado por arquivos em MOCK_STATE_DIR:
  health.status   -> conteúdo do campo status ("UP"/"DOWN"); default UP
  token.mode      -> "ok" (aceita user/pass corretos) | "reject" (401 sempre)
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

STATE_DIR = os.environ.get("MOCK_STATE_DIR", "/tmp/raiji-mock-state")
REALM = "raiji"
CLIENT_ID = "raiji-api"
USER = os.environ.get("MOCK_USER", "teste")
PASSWORD = os.environ.get("MOCK_PASSWORD", "teste123")
FAKE_TOKEN = "mock-access-token-abc123"


def _state(name, default):
    try:
        with open(os.path.join(STATE_DIR, name), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return default


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silencioso
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/actuator/health":
            status = _state("health.status", "UP")
            self._send(200, {"status": status})
        elif self.path.startswith(f"/realms/{REALM}"):
            self._send(200, {"realm": REALM, "public_key": "xxx"})
        elif self.path == "/api/me":
            auth = self.headers.get("Authorization", "")
            if auth == f"Bearer {FAKE_TOKEN}":
                self._send(200, {"id": 1, "username": USER})
            else:
                self._send(401, {"error": "unauthorized"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == f"/realms/{REALM}/protocol/openid-connect/token":
            length = int(self.headers.get("Content-Length", 0))
            form = parse_qs(self.rfile.read(length).decode())
            mode = _state("token.mode", "ok")
            creds_ok = (
                form.get("username", [""])[0] == USER
                and form.get("password", [""])[0] == PASSWORD
                and form.get("client_id", [""])[0] == CLIENT_ID
                and form.get("grant_type", [""])[0] == "password"
            )
            if mode == "ok" and creds_ok:
                self._send(200, {"access_token": FAKE_TOKEN, "token_type": "Bearer", "expires_in": 300})
            else:
                self._send(401, {"error": "invalid_grant"})
        else:
            self._send(404, {"error": "not found"})


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    port = int(os.environ.get("MOCK_PORT", "18080"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock listening on 127.0.0.1:{port} (state: {STATE_DIR})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
@@MOCK_PY@@

write_file tests/cicd/curl-shim/curl <<'@@CURL_SHIM@@'
#!/usr/bin/env python3
"""Shim de `curl` para testes: implementa o subconjunto usado pelo smoke-test.sh
(-sS -o FILE -w '%{http_code}' --max-time N -X METHOD -H K:V --data BODY URL)
usando urllib. Permite exercitar o ramo 'curl' do script em máquinas sem curl.
"""
import sys
import urllib.error
import urllib.request

args = sys.argv[1:]
out = None
write_out = None
method = "GET"
data = None
headers = {}
url = None
timeout = 10

i = 0
while i < len(args):
    a = args[i]
    if a == "-sS":
        pass
    elif a == "-o":
        i += 1
        out = args[i]
    elif a == "-w":
        i += 1
        write_out = args[i]
    elif a == "--max-time":
        i += 1
        timeout = float(args[i])
    elif a == "-X":
        i += 1
        method = args[i]
    elif a == "-H":
        i += 1
        k, _, v = args[i].partition(":")
        headers[k] = v.strip()
    elif a == "--data":
        i += 1
        data = args[i].encode()
    elif not a.startswith("-"):
        url = a
    i += 1

code = 0
body = b""
try:
    req = urllib.request.Request(url, method=method, data=data)
    for k, v in headers.items():
        req.add_header(k, v)
    r = urllib.request.urlopen(req, timeout=timeout)
    code, body = r.status, r.read()
except urllib.error.HTTPError as e:
    code, body = e.code, (e.read() if e.fp else b"")
except Exception as e:
    code, body = 0, str(e).encode()

if out:
    with open(out, "wb") as f:
        f.write(body)
else:
    sys.stdout.buffer.write(body)
if write_out:
    sys.stdout.write(write_out.replace("%{http_code}", "%03d" % code))
@@CURL_SHIM@@

write_file tests/cicd/test_smoke_functional.sh <<'@@FUNC_TEST_SH@@'
#!/usr/bin/env bash
# =============================================================================
# test_smoke_functional.sh — valida o COMPORTAMENTO do scripts/smoke-test.sh
# contra um servidor mock (Kong+App+Keycloak simulados):
#   cenário 1: tudo saudável, modo serial    -> exit 0, 4 PASS
#   cenário 2: tudo saudável, modo paralelo  -> exit 0
#   cenário 3: --check health isolado        -> exit 0
#   cenário 4: senha errada                  -> exit 1, token/me FAIL, health PASS
#   cenário 5: health DOWN                   -> exit 1
#   cenário 6: servidor fora do ar           -> exit 1 (retries=1 p/ ser rápido)
# =============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PORT="${MOCK_PORT:-18080}"
STATE_DIR="$(mktemp -d)"
export MOCK_PORT="$PORT" MOCK_STATE_DIR="$STATE_DIR"

PASS=0; FAIL=0
t_ok() { echo "  ✅ $*"; PASS=$((PASS+1)); }
t_ko() { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
assert_exit() { # esperado cmd...
  local want="$1"; shift
  "$@" >/dev/null 2>&1
  local got=$?
  [[ "$got" == "$want" ]]
}

cleanup() {
  [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$STATE_DIR"
}
trap cleanup EXIT

echo "== subindo mock server na porta $PORT =="
python3 tests/cicd/mock_server.py & SERVER_PID=$!
for _ in $(seq 1 50); do
  python3 - "http://127.0.0.1:$PORT/actuator/health" <<'PY' && break
import sys, urllib.request
try: urllib.request.urlopen(sys.argv[1], timeout=1)
except Exception: sys.exit(1)
PY
  sleep 0.2
done

SMOKE=(./scripts/smoke-test.sh --base "http://127.0.0.1:$PORT" --retries 1 --retry-delay 0)
SMOKE_ENV=(env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123)

echo "== cenário 1: saudável, serial =="
if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --serial; then t_ok "todas as checagens passam (serial)"; else t_ko "deveria passar (serial)"; fi
OUT="$("${SMOKE_ENV[@]}" "${SMOKE[@]}" --serial 2>&1)"
for c in health realm token me; do
  if grep -Eq "\[PASS\].*\b$c\b" <<<"$OUT"; then t_ok "check $c reportado"; else t_ko "check $c não reportado"; fi
done

echo "== cenário 2: saudável, paralelo =="
if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --parallel; then t_ok "todas as checagens passam (paralelo)"; else t_ko "deveria passar (paralelo)"; fi

echo "== cenário 3: --check isolado =="
for c in health realm token me; do
  if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --check "$c"; then t_ok "--check $c ok"; else t_ko "--check $c deveria passar"; fi
done

echo "== cenário 4: credenciais inválidas (token 401) =="
env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com senha errada" || t_ko "deveria falhar com senha errada"
OUT="$(env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial 2>&1)"
grep -Eq "\[PASS\].*\bhealth\b" <<<"$OUT" && t_ok "health continua PASS (falha parcial detectada)" || t_ko "health deveria passar"
grep -Eq "\[FAIL\].*\btoken\b" <<<"$OUT" && t_ok "token FAIL reportado" || t_ko "token deveria falhar"
grep -Eq "\[FAIL\].*\bme\b" <<<"$OUT" && t_ok "me FAIL reportado (depende do token)" || t_ko "me deveria falhar"

echo "== cenário 5: health DOWN =="
echo DOWN > "$STATE_DIR/health.status"
env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 "${SMOKE[@]}" --check health >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com status DOWN" || t_ko "deveria falhar com DOWN"
echo UP > "$STATE_DIR/health.status"

echo "== cenário 6: servidor fora do ar =="
env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 \
  ./scripts/smoke-test.sh --base "http://127.0.0.1:$((PORT+1))" --retries 1 --retry-delay 0 >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com servidor indisponível" || t_ko "deveria falhar sem servidor"

echo "== cenário 7: ramo HTTP via curl (shim) =="
SHIM_DIR="$ROOT/tests/cicd/curl-shim"
chmod +x "$SHIM_DIR/curl" 2>/dev/null
if [[ -x "$SHIM_DIR/curl" ]]; then
  ( export PATH="$SHIM_DIR:$PATH"
    command -v curl >/dev/null && "${SMOKE_ENV[@]}" "${SMOKE[@]}" --parallel ) >/dev/null 2>&1
  [[ $? -eq 0 ]] && t_ok "modo curl: todas as checagens passam" || t_ko "modo curl: deveria passar"
  ( export PATH="$SHIM_DIR:$PATH"
    env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial ) >/dev/null 2>&1
  [[ $? -eq 1 ]] && t_ok "modo curl: exit=1 com senha errada" || t_ko "modo curl: deveria falhar com senha errada"
else
  echo "  (shim de curl indisponível — pulado)"
fi

GUARDIAN_ENV=(env ENVIRONMENT=dev "BASE_URL=http://127.0.0.1:$PORT"
              SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123
              GUARDIAN_RETRY=1 GUARDIAN_DELAY=0
              GUARDIAN_RECHECK_DELAY=1
              GUARDIAN_RETRY_RECHECK=1 GUARDIAN_DELAY_RECHECK=0)

echo "== cenário 8: guardian — ambiente saudável =="
echo UP > "$STATE_DIR/health.status"
OUT="$("${GUARDIAN_ENV[@]}" ./scripts/aws/guardian.sh 2>&1)"
if [[ $? -eq 0 ]] && grep -q "saudável" <<<"$OUT"; then
  t_ok "guardian exit=0 com ambiente saudável"
else
  t_ko "guardian deveria sair 0 (saudável): $OUT"
fi

echo "== cenário 9: guardian — falha transitória (recupera na reconfirmação) =="
echo DOWN > "$STATE_DIR/health.status"
( sleep 2; echo UP > "$STATE_DIR/health.status" ) & TOGGLER=$!
OUT="$(env ENVIRONMENT=dev "BASE_URL=http://127.0.0.1:$PORT" \
      SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 \
      GUARDIAN_RETRY=1 GUARDIAN_DELAY=0 GUARDIAN_RECHECK_DELAY=3 \
      GUARDIAN_RETRY_RECHECK=10 GUARDIAN_DELAY_RECHECK=1 \
      ./scripts/aws/guardian.sh 2>&1)"
RC=$?
wait "$TOGGLER" 2>/dev/null
if [[ $RC -eq 0 ]] && grep -q "transitória" <<<"$OUT"; then
  t_ok "guardian exit=0 detectando falha transitória na reconfirmação"
else
  t_ko "guardian deveria recuperar na reconfirmação (rc=$RC): $OUT"
fi

echo "== cenário 10: guardian — falha confirmada SEM versão anterior =="
echo DOWN > "$STATE_DIR/health.status"
OUT="$("${GUARDIAN_ENV[@]}" ./scripts/aws/guardian.sh 2>&1)"
RC=$?
echo UP > "$STATE_DIR/health.status"
if [[ $RC -eq 1 ]] && grep -q "SEM versão anterior" <<<"$OUT"; then
  t_ok "guardian exit=1 + alerta 'SEM versão anterior' (rollback só com histórico)"
else
  t_ko "guardian deveria falhar sem versão anterior (rc=$RC): $OUT"
fi

echo
echo "resultado: $PASS passaram, $FAIL falharam"
[[ "$FAIL" -eq 0 ]]
@@FUNC_TEST_SH@@

write_file tests/cicd/test_cicd.py <<'@@TEST_PY@@'
#!/usr/bin/env python3
"""Testes estruturais da S1-03 — verificam se TUDO da issue foi implementado.

Rodam sem dependências obrigatórias (PyYAML é usado quando disponível para
validação profunda dos workflows; sem ele, os testes YAML são pulados).
"""
import os
import re
import stat
import subprocess
import unittest

try:
    import yaml  # type: ignore
    HAS_YAML = True
except ImportError:
    HAS_YAML = False

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def path(*parts):
    return os.path.join(ROOT, *parts)


def read(*parts):
    with open(path(*parts), encoding="utf-8") as f:
        return f.read()


if HAS_YAML:
    class _Loader(yaml.SafeLoader):
        """SafeLoader que tolera tags do Compose Spec (!override, !reset)."""

    def _any_tag(loader, tag_suffix, node):
        if isinstance(node, yaml.SequenceNode):
            return loader.construct_sequence(node, deep=True)
        if isinstance(node, yaml.MappingNode):
            return loader.construct_mapping(node, deep=True)
        return loader.construct_scalar(node)

    _Loader.add_multi_constructor("!", _any_tag)

    def load_yaml(*parts):
        return yaml.load(read(*parts), Loader=_Loader)
else:  # pragma: no cover
    def load_yaml(*parts):
        raise unittest.SkipTest("PyYAML indisponível")


def is_executable(*parts):
    return os.stat(path(*parts)).st_mode & stat.S_IXUSR != 0


REQUIRED_FILES = [
    ".github/workflows/ci.yml",
    ".github/workflows/deploy.yml",
    ".github/workflows/deploy-dev.yml",
    ".github/workflows/deploy-homolog.yml",
    ".github/workflows/rollback.yml",
    "scripts/smoke-test.sh",
    "scripts/aws/deploy.sh",
    "scripts/aws/deploy-remote.sh",
    "scripts/aws/rollback.sh",
    "scripts/aws/ecr-retag.sh",
    "scripts/aws/bootstrap-aws.sh",
    "docker-compose.aws.yml",
    "docs/DEPLOY.md",
    "infra/aws/aws-config.env",
    ".github/workflows/guardian.yml",
    "scripts/aws/guardian.sh",
    "tests/cicd/run-all.sh",
    "tests/cicd/mock_server.py",
    "tests/cicd/test_smoke_functional.sh",
    "tests/cicd/test_cicd.py",
    "tests/cicd/curl-shim/curl",
]

EXECUTABLE_FILES = [
    "scripts/smoke-test.sh",
    "scripts/aws/deploy.sh",
    "scripts/aws/deploy-remote.sh",
    "scripts/aws/rollback.sh",
    "scripts/aws/ecr-retag.sh",
    "scripts/aws/bootstrap-aws.sh",
    "scripts/aws/guardian.sh",
    "scripts/setup-cicd.sh",
    "tests/cicd/run-all.sh",
    "tests/cicd/test_smoke_functional.sh",
]


class TestArquivosExistentes(unittest.TestCase):
    def test_arquivos_criados(self):
        for f in REQUIRED_FILES:
            with self.subTest(arquivo=f):
                self.assertTrue(os.path.isfile(path(f)), f"faltando: {f}")

    def test_scripts_executaveis(self):
        for f in EXECUTABLE_FILES:
            with self.subTest(arquivo=f):
                self.assertTrue(is_executable(f), f"sem +x: {f}")

    def test_sintaxe_bash(self):
        for f in EXECUTABLE_FILES:
            with self.subTest(arquivo=f):
                r = subprocess.run(["bash", "-n", path(f)], capture_output=True)
                self.assertEqual(r.returncode, 0, r.stderr.decode())


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestCiWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wf = load_yaml(".github", "workflows", "ci.yml")

    def test_gatilhos_pr_e_push(self):
        on = self.wf[True] if True in self.wf else self.wf["on"]  # 'on' vira True no YAML
        self.assertIn("pull_request", on)
        self.assertIn("push", on)
        self.assertEqual(set(on["push"]["branches"]), {"main", "develop"})

    def test_concurrency_cancela_antigos(self):
        self.assertTrue(self.wf["concurrency"]["cancel-in-progress"])

    def test_matriz_dinamica_paralela(self):
        ut = self.wf["jobs"]["unit-tests"]
        self.assertEqual(ut["strategy"]["fail-fast"], False)
        self.assertIn("fromJSON", str(ut["strategy"]["matrix"]["module"]))
        self.assertIn("detect-modules", ut["needs"])

    def test_pipeline_da_issue(self):
        steps = self.wf["jobs"]["verify"]["steps"]
        blob = str(steps)
        self.assertIn("actions/checkout", blob)                       # checkout
        self.assertIn("actions/setup-java", blob)                     # setup JDK
        java_step = [s for s in steps if str(s.get("uses", "")).startswith("actions/setup-java")][0]
        self.assertEqual(str(java_step["with"]["java-version"]), "21")
        self.assertEqual(java_step["with"]["cache"], "maven")         # cache Maven
        verify_cmds = [s.get("run", "") for s in steps if "clean verify" in s.get("run", "")]
        self.assertTrue(verify_cmds, "etapa 'mvn clean verify' ausente")
        uploads = [s for s in steps if str(s.get("uses", "")).startswith("actions/upload-artifact")]
        self.assertTrue(any("jacoco" in str(s["with"]["name"]).lower() or
                            "jacoco" in str(s["with"]["path"]).lower() for s in uploads),
                        "upload do relatório JaCoCo ausente")

    def test_build_docker_paralelo(self):
        self.assertIn("docker-image", self.wf["jobs"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestDeployWorkflows(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.deploy = load_yaml(".github", "workflows", "deploy.yml")
        cls.dev = load_yaml(".github", "workflows", "deploy-dev.yml")
        cls.homolog = load_yaml(".github", "workflows", "deploy-homolog.yml")
        cls.rollback = load_yaml(".github", "workflows", "rollback.yml")

    @staticmethod
    def _on(wf):
        return wf[True] if True in wf else wf["on"]

    def test_dev_dispara_em_develop(self):
        self.assertEqual(self._on(self.dev)["push"]["branches"], ["develop"])

    def test_homolog_dispara_em_main(self):
        self.assertEqual(self._on(self.homolog)["push"]["branches"], ["main"])

    def test_ambos_chamam_workflow_reutilizavel(self):
        for wf, env in ((self.dev, "dev"), (self.homolog, "homolog")):
            job = list(wf["jobs"].values())[0]
            self.assertTrue(job["uses"].endswith(".github/workflows/deploy.yml"))
            self.assertEqual(job["with"]["environment"], env)

    def test_deploy_e_workflow_call_com_secrets(self):
        self.assertIn("workflow_call", self._on(self.deploy))
        secrets = self._on(self.deploy)["workflow_call"]["secrets"]
        for s in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "SSH_PRIVATE_KEY",
                  "DB_PASSWORD", "KEYCLOAK_ADMIN_CLIENT_SECRET"):
            self.assertIn(s, secrets)

    @staticmethod
    def _needs(job):
        n = job.get("needs", [])
        return [n] if isinstance(n, str) else list(n)

    def test_fluxo_build_deploy_smoke_release(self):
        jobs = self.deploy["jobs"]
        for j in ("config", "tests", "build-push", "deploy", "smoke", "release", "rollback"):
            self.assertIn(j, jobs)
        self.assertIn("tests", self._needs(jobs["build-push"]))
        self.assertIn("build-push", self._needs(jobs["deploy"]))
        self.assertIn("deploy", self._needs(jobs["smoke"]))
        # release bloqueado pelo smoke
        self.assertIn("smoke", self._needs(jobs["release"]))
        self.assertEqual(jobs["release"]["if"], "success()")

    def test_smoke_paralelo_4_checagens(self):
        smoke = self.deploy["jobs"]["smoke"]
        self.assertEqual(smoke["strategy"]["fail-fast"], False)
        self.assertEqual(smoke["strategy"]["matrix"]["check"], ["health", "realm", "token", "me"])
        run = str(smoke["steps"][-1]["run"])
        self.assertIn("smoke-test.sh", run)
        self.assertIn("--check", run)

    def test_rollback_automatico_em_falha(self):
        rb = self.deploy["jobs"]["rollback"]
        self.assertIn("failure()", rb["if"])
        self.assertIn("rollback.sh", str(rb["steps"][-1]["run"]))

    def test_imagem_vai_para_ecr(self):
        blob = str(self.deploy["jobs"]["build-push"]["steps"])
        self.assertIn("amazon-ecr-login", blob)
        self.assertIn("docker/build-push-action", blob)
        self.assertIn("ecr-retag.sh", blob)  # preserva <env>-previous

    def test_rollback_manual_dispatch(self):
        self.assertIn("workflow_dispatch", self._on(self.rollback))
        opts = self._on(self.rollback)["workflow_dispatch"]["inputs"]["environment"]["options"]
        self.assertEqual(list(opts), ["dev", "homolog"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestComposeAws(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c = load_yaml("docker-compose.aws.yml")

    def test_variaveis_da_issue_no_provedor(self):
        env = self.c["services"]["app"]["environment"]
        for v in ("SPRING_PROFILES_ACTIVE", "KEYCLOAK_ADMIN_CLIENT_SECRET",
                  "DB_URL", "DB_USER", "DB_PASSWORD", "JWK_SET_URI"):
            self.assertIn(v, env, f"variável {v} ausente no ambiente do app")

    def test_app_usa_imagem_ecr(self):
        self.assertIn("${ECR_REGISTRY", self.c["services"]["app"]["image"])
        self.assertIn("${IMAGE_TAG", self.c["services"]["app"]["image"])

    def test_banco_e_app_nao_expostos(self):
        self.assertEqual(self.c["services"]["postgres"]["ports"], [])
        self.assertEqual(self.c["services"]["app"]["ports"], [])

    def test_kong_unico_ponto_publico(self):
        self.assertIn("${BASE_PORT", str(self.c["services"]["kong"]["ports"]))


class TestSmokeScript(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = read("scripts", "smoke-test.sh")

    def test_quatro_checagens_da_issue(self):
        self.assertIn("/actuator/health", self.src)
        self.assertIn('"status"', self.src)
        self.assertIn("/realms/$REALM", self.src)
        self.assertIn("/protocol/openid-connect/token", self.src)
        self.assertIn("/api/me", self.src)
        self.assertIn("Bearer", self.src)

    def test_modo_paralelo_e_serial(self):
        self.assertIn("--parallel", self.src)
        self.assertIn("--serial", self.src)
        self.assertIn("run_one", self.src)
        self.assertRegex(self.src, r"&\s*\n\s*pids\+=")  # jobs em background + wait

    def test_check_individual_para_matriz(self):
        self.assertIn("--check", self.src)
        for c in ("health", "realm", "token", "me"):
            self.assertIn(f"check_{c}()", self.src)

    def test_fallback_sem_curl(self):
        self.assertIn("python3", self.src)
        self.assertIn("urllib.request", self.src)

    def test_retries(self):
        self.assertIn("run_with_retries", self.src)
        self.assertIn("RETRY_DELAY", self.src)


class TestRollbackScript(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = read("scripts", "aws", "rollback.sh")
        cls.retag = read("scripts", "aws", "ecr-retag.sh")

    def test_retag_da_imagem_anterior(self):
        self.assertIn("ecr-retag.sh", self.src)
        self.assertIn("-previous", self.src)
        self.assertIn("-current", self.src)
        self.assertIn("batch-get-image", self.retag)
        self.assertIn("put-image", self.retag)

    def test_redeploy_e_smoke_pos_rollback(self):
        self.assertIn("compose", self.src)
        self.assertIn("up -d", self.src)
        self.assertIn("smoke-test.sh", self.src)


class TestDeployDoc(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = read("docs", "DEPLOY.md")

    def test_urls_documentadas(self):
        self.assertIn("URL", self.doc)
        self.assertIn("8000", self.doc)
        self.assertIn("9000", self.doc)

    def test_variaveis_documentadas(self):
        for v in ("SPRING_PROFILES_ACTIVE", "KEYCLOAK_ADMIN_CLIENT_SECRET",
                  "DB_URL", "DB_USER", "DB_PASSWORD", "JWK_SET_URI"):
            self.assertIn(v, self.doc, f"{v} ausente em docs/DEPLOY.md")

    def test_passos_de_rollback(self):
        low = self.doc.lower()
        self.assertIn("rollback", low)
        self.assertIn("retag", low)
        self.assertIn("redeploy", low)

    def test_branches_e_ambientes(self):
        self.assertIn("develop", self.doc)
        self.assertIn("main", self.doc)
        self.assertIn("dev", self.doc)
        self.assertIn("homolog", self.doc)

    def test_limitacoes_documentadas(self):
        self.assertRegex(self.doc, r"[Ll]imitaç")

    def test_secrets_github_documentados(self):
        for s in ("AWS_ACCESS_KEY_ID", "AWS_SSH_KEY_DEV", "AWS_SSH_KEY_HOMOLOG",
                  "DB_PASSWORD_DEV", "DB_PASSWORD_HOMOLOG"):
            self.assertIn(s, self.doc)

    def test_como_rodar_os_testes(self):
        self.assertIn("tests/cicd/run-all.sh", self.doc)

    def test_config_facil_documentada(self):
        self.assertIn("aws-config.env", self.doc)
        self.assertIn("INSTANCE_TYPE_DEV", self.doc)
        self.assertIn("INSTANCE_TYPE_HOMOLOG", self.doc)
        self.assertRegex(self.doc, r"tipo de máquina|tipo da máquina")

    def test_guardian_documentado(self):
        self.assertIn("Guardian", self.doc)
        self.assertIn("guardian.yml", self.doc)

    def test_protecao_sem_versao_anterior_documentada(self):
        self.assertIn("NÃO existe versão anterior", self.doc)


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestConfigAwsFacil(unittest.TestCase):
    """Configuração AWS centralizada: 1 arquivo, lido por bootstrap e workflows."""

    @classmethod
    def setUpClass(cls):
        cls.cfg = read("infra", "aws", "aws-config.env")
        cls.deploy = load_yaml(".github", "workflows", "deploy.yml")
        cls.bootstrap = read("scripts", "aws", "bootstrap-aws.sh")

    def test_arquivo_config_com_todas_as_chaves(self):
        for key in ("AWS_REGION",
                    "INSTANCE_TYPE_DEV", "INSTANCE_TYPE_HOMOLOG",
                    "BASE_PORT_DEV", "BASE_PORT_HOMOLOG",
                    "KC_PORT_DEV", "KC_PORT_HOMOLOG"):
            with self.subTest(chave=key):
                self.assertRegex(self.cfg, rf"(?m)^{key}=", f"{key} ausente em aws-config.env")

    def test_bootstrap_le_o_config(self):
        self.assertIn("aws-config.env", self.bootstrap)
        self.assertIn("--config", self.bootstrap)
        self.assertIn("INSTANCE_TYPE_", self.bootstrap)

    def test_bootstrap_redimensiona_instancia(self):
        # mudar o tipo de máquina = editar o config e rerodar o bootstrap
        self.assertIn("modify-instance-attribute", self.bootstrap)
        self.assertIn("stop-instances", self.bootstrap)
        self.assertIn("start-instances", self.bootstrap)

    def test_deploy_tem_job_config(self):
        cfg = self.deploy["jobs"]["config"]
        self.assertIn("aws-region", cfg["outputs"])
        self.assertIn("base-port", cfg["outputs"])
        self.assertIn("infra/aws/aws-config.env", str(cfg["steps"]))

    def test_portas_e_regiao_nao_hardcoded_nos_triggers(self):
        for f in ("deploy-dev.yml", "deploy-homolog.yml"):
            wf = load_yaml(".github", "workflows", f)
            job = list(wf["jobs"].values())[0]
            with self.subTest(workflow=f):
                self.assertNotIn("base-port", job["with"])
                self.assertNotIn("aws-region", job["with"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestGuardian(unittest.TestCase):
    """Vigilância contínua: erro inesperado pós-deploy -> rollback + alerta."""

    @classmethod
    def setUpClass(cls):
        cls.wf = load_yaml(".github", "workflows", "guardian.yml")
        cls.sh = read("scripts", "aws", "guardian.sh")

    def test_agendado_e_manual(self):
        on = self.wf[True] if True in self.wf else self.wf["on"]
        self.assertIn("schedule", on)
        self.assertIn("cron", str(on["schedule"]))
        self.assertIn("workflow_dispatch", on)

    def test_dois_ambientes_paralelos_serializados_com_deploy(self):
        jobs = self.wf["jobs"]
        self.assertIn("guard-dev", jobs)
        self.assertIn("guard-homolog", jobs)
        # mesmo concurrency group do deploy: nunca concorre com um deploy
        self.assertEqual(jobs["guard-dev"]["concurrency"]["group"], "deploy-dev")
        self.assertEqual(jobs["guard-homolog"]["concurrency"]["group"], "deploy-homolog")

    def test_abre_issue_de_alerta(self):
        self.assertEqual(self.wf["permissions"]["issues"], "write")
        blob = str(self.wf["jobs"]["guard-dev"]["steps"])
        self.assertIn("github-script", blob)
        self.assertIn("issues.create", blob)

    def test_reconfirma_antes_de_reverter(self):
        self.assertIn("GUARDIAN_RECHECK_DELAY", self.sh)
        self.assertIn("sleep", self.sh)

    def test_so_rollback_se_houver_versao_anterior(self):
        self.assertIn("describe-images", self.sh)
        self.assertIn("-previous", self.sh)
        self.assertIn("rollback.sh", self.sh)


class TestProtecaoRelease(unittest.TestCase):
    """Versão quebrada nunca fica no ar — nem em dev, nem em homolog."""

    @classmethod
    def setUpClass(cls):
        cls.remote = read("scripts", "aws", "deploy-remote.sh")
        cls.rollback = read("scripts", "aws", "rollback.sh")
        cls.deploy_wf = read(".github", "workflows", "deploy.yml")

    def test_health_gate_bloqueia_deploy(self):
        self.assertIn("smoke-test.sh --check health", self.remote)
        self.assertIn("exit 1", self.remote)

    def test_selfrollback_para_versao_anterior(self):
        self.assertIn("PREVIOUS_TAG", self.remote)
        self.assertIn("self-rollback", self.remote)

    def test_sem_anterior_para_o_app_nao_serve_quebrado(self):
        self.assertIn("stop app", self.remote)
        self.assertIn("stop app", self.rollback)
        self.assertIn("SEM versão anterior", self.remote)

    def test_rollback_verifica_anterior_no_ecr(self):
        self.assertIn("describe-images", self.rollback)
        self.assertIn("-previous", self.rollback)

    def test_rollback_automatico_no_workflow(self):
        self.assertIn("failure()", self.deploy_wf)
        self.assertIn("rollback.sh", self.deploy_wf)


class TestSemSegredosCommitados(unittest.TestCase):
    ARQUIVOS = [
        ".github/workflows/ci.yml",
        ".github/workflows/deploy.yml",
        ".github/workflows/deploy-dev.yml",
        ".github/workflows/deploy-homolog.yml",
        ".github/workflows/rollback.yml",
        ".github/workflows/guardian.yml",
        "scripts/aws/deploy.sh",
        "scripts/aws/rollback.sh",
        "scripts/aws/guardian.sh",
        "scripts/aws/bootstrap-aws.sh",
        "docker-compose.aws.yml",
        "infra/aws/aws-config.env",
    ]
    PADROES = [
        re.compile(r"AKIA[0-9A-Z]{16}"),                    # AWS access key
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),  # chaves privadas
        re.compile(r"(?i)(aws_secret_access_key|ssh_private_key)\s*[:=]\s*['\"]?[A-Za-z0-9/+=]{20,}"),
    ]

    def test_arquivos_nao_contem_segredos(self):
        for f in self.ARQUIVOS:
            src = read(f)
            for p in self.PADROES:
                with self.subTest(arquivo=f, padrao=p.pattern):
                    self.assertIsNone(p.search(src))

    def test_segredos_vem_do_github(self):
        dev = read(".github", "workflows", "deploy-dev.yml")
        self.assertIn("secrets.AWS_SSH_KEY_DEV", dev)
        self.assertIn("secrets.DB_PASSWORD_DEV", dev)
        hom = read(".github", "workflows", "deploy-homolog.yml")
        self.assertIn("secrets.AWS_SSH_KEY_HOMOLOG", hom)
        self.assertIn("secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG", hom)

    def test_env_remoto_nao_commitado(self):
        gi = read(".gitignore")
        self.assertIn(".env", gi)
        self.assertIn("*.pem", gi)


if __name__ == "__main__":
    unittest.main(verbosity=2)
@@TEST_PY@@

# =============================================================================
# 14) docs/DEPLOY.md
# =============================================================================
write_file docs/DEPLOY.md <<'@@DEPLOY_MD@@'
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
@@DEPLOY_MD@@

# =============================================================================
# 15) .gitignore (append idempotente)
# =============================================================================
touch .gitignore
while IFS= read -r line; do
  grep -qxF "$line" .gitignore || echo "$line" >> .gitignore
done <<'@@GITIGNORE@@'

# --- CI/CD (S1-03) ---
.env
*.pem
.deploy-state
.deploy-state.previous
.tools/
__pycache__/
*.pyc
@@GITIGNORE@@
log "gravado: .gitignore (append)"

# =============================================================================
# Permissões de execução
# =============================================================================
chmod +x scripts/smoke-test.sh \
         scripts/aws/deploy.sh scripts/aws/deploy-remote.sh \
         scripts/aws/rollback.sh scripts/aws/ecr-retag.sh scripts/aws/bootstrap-aws.sh \
         scripts/aws/guardian.sh \
         scripts/setup-cicd.sh \
         tests/cicd/run-all.sh tests/cicd/test_smoke_functional.sh tests/cicd/test_cicd.py
log "permissões +x aplicadas"

echo
log "============================================================"
log " S1-03 implementada com sucesso!"
log "============================================================"
log " Arquivos gerados:"
log "   infra/aws/aws-config.env  (fonte única: tipo de máquina, região, portas)"
log "   .github/workflows/{ci,deploy,deploy-dev,deploy-homolog,rollback,guardian}.yml"
log "   scripts/smoke-test.sh"
log "   scripts/aws/{deploy,deploy-remote,rollback,ecr-retag,bootstrap-aws,guardian}.sh"
log "   docker-compose.aws.yml"
log "   docs/DEPLOY.md"
log "   tests/cicd/{run-all.sh,test_cicd.py,test_smoke_functional.sh,mock_server.py}"
echo
log " Próximos passos:"
log "   1) tests/cicd/run-all.sh                # valida tudo"
log "   2) ./scripts/aws/bootstrap-aws.sh --env dev"
log "   3) ./scripts/aws/bootstrap-aws.sh --env homolog"
log "   4) configure secrets/variables no GitHub (o bootstrap imprime a lista)"
log "   5) git add . && git commit && git push  # develop->dev | main->homolog"
echo

if [[ "$MODE" == "check" ]]; then
  exec bash tests/cicd/run-all.sh
fi
