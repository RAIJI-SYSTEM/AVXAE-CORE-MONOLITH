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
