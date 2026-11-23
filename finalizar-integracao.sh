#!/usr/bin/env bash
# =============================================================================
# finalizar-integracao.sh
#
# Continua o fix-merge-final.sh, que parou APENAS na checagem de duplicidades
# de migracoes Flyway. Essa checagem deu FALSO POSITIVO porque contava as
# copias compiladas em */target/classes/db/migration (lixo de builds antigos).
#
# Estado atual esperado (pos fix-merge-final.sh, sem commit):
#   - HEAD = 2a6f680 (backup/antes-fix-deliveries)
#   - Delivery implementa Payable + repositorio/response/service/teste patched
#   - DeliveryPaymentService/DeliveryRefunds/DeliveryPaymentController portados
#   - Migracoes renumeradas: rides V22->V27, V24->V28, V25->V29, V26->V30,
#     deliveries V23->V31 (idempotente)
#
# Este script:
#   0) Pre-checagens (branch, HEAD, backup, raiz do repo)
#   1) Revalida o estado acima
#   2) Checagem REAL de versoes Flyway (somente */src/main/resources/db/migration)
#   3) Compila raiji-deliveries + dependencias (gate antes do commit)
#   4) Commit de tudo (ignora logs de diagnostico)
#   5) Build completo do monolito
#   6) Imprime proximos passos (push, banco, Docker)
#
# Rollback de emergencia:  git reset --hard backup/antes-fix-deliveries
# Se o passo 1 detectar estado inesperado, recupere com:
#   git reset --hard backup/antes-fix-deliveries
#   bash fix-merge-final.sh        (vai parar de novo na checagem — tudo bem)
#   bash finalizar-integracao.sh
# =============================================================================
set -euo pipefail

BRANCH_ESPERADA="feature/integracao-completa"
HEAD_ESPERADO="2a6f680"
BACKUP_REF="backup/antes-fix-deliveries"
LOG_DIR=".fix-merge-logs"
mkdir -p "$LOG_DIR"

say(){ printf '%s\n' "$*"; }
ok(){ printf '  OK %s\n' "$*"; }
warn(){ printf 'AVISO %s\n' "$*"; }
die(){ printf 'ERRO %s\n' "$*" >&2; exit 1; }

say "==> 0) Pre-checagens"
[ -d raiji-deliveries ] || die "Rode este script na raiz do repositorio (~/Documentos/AVXAE-CORE-MONOLITH)."
command -v mvn >/dev/null 2>&1 || die "mvn nao encontrado no PATH."
command -v git >/dev/null 2>&1 || die "git nao encontrado no PATH."

BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$BRANCH" = "$BRANCH_ESPERADA" ] || die "Branch inesperada '$BRANCH' (esperada: $BRANCH_ESPERADA)."
ok "Branch: $BRANCH"

HEAD_SHORT=$(git rev-parse --short HEAD)
if [ "$HEAD_SHORT" = "$HEAD_ESPERADO" ]; then
  ok "HEAD = $HEAD_SHORT (estado do merge com patches aplicados, sem commit)"
else
  warn "HEAD = $HEAD_SHORT (esperado $HEAD_ESPERADO). Se algo foi commitado manualmente, revise antes de prosseguir."
fi

git rev-parse --verify --quiet "$BACKUP_REF" >/dev/null || die "Backup '$BACKUP_REF' nao existe — nao e possivel garantir rollback."
ok "Backup disponivel: $BACKUP_REF ($(git rev-parse --short "$BACKUP_REF"))"

# -----------------------------------------------------------------------------
say "==> 1) Revalidando o estado deixado pelo fix-merge-final.sh"

DELIVERY_JAVA="raiji-deliveries/src/main/java/com/raiji/deliveries/Delivery.java"
REPO_JAVA="raiji-deliveries/src/main/java/com/raiji/deliveries/repository/DeliveryRepository.java"

grep -q "implements.*Payable" "$DELIVERY_JAVA" \
  || die "$DELIVERY_JAVA nao implementa Payable (estado inesperado). Veja o cabecalho do script para recuperar."
grep -q "findByPaymentIntentId" "$REPO_JAVA" \
  || die "$REPO_JAVA sem findByPaymentIntentId (estado inesperado)."
ok "Delivery implementa Payable; repositorio tem findByPaymentIntentId"

for f in DeliveryPaymentService.java DeliveryRefunds.java DeliveryPaymentController.java; do
  p=$(find raiji-deliveries/src -type f -name "$f" -print -quit)
  if [ -z "$p" ]; then
    die "Arquivo portado ausente: $f (estado inesperado)."
  fi
  ok "Portado presente: $p"
done

say "-- Migracoes renumeradas (devem existir em src):"
for par in \
  "raiji-rides|V27__ride_passenger_payment_status.sql" \
  "raiji-rides|V28__driver_payouts.sql" \
  "raiji-rides|V29__push_devices.sql" \
  "raiji-rides|V30__coupons_rewards.sql" \
  "raiji-deliveries|V31__delivery_payment_status.sql"; do
  mod="${par%%|*}"; arq="${par#*|}"
  if [ ! -f "$mod/src/main/resources/db/migration/$arq" ]; then
    die "Renomeacao ausente: $mod/src/main/resources/db/migration/$arq (estado inesperado)."
  fi
  ok "$mod/src/main/resources/db/migration/$arq"
done

say "-- Nomes antigos da feature (nao devem existir em src):"
for par in \
  "raiji-rides|V22__ride_passenger_payment_status.sql" \
  "raiji-rides|V24__driver_payouts.sql" \
  "raiji-rides|V25__push_devices.sql" \
  "raiji-rides|V26__coupons_rewards.sql" \
  "raiji-deliveries|V23__delivery_payment_status.sql"; do
  mod="${par%%|*}"; arq="${par#*|}"
  if [ -f "$mod/src/main/resources/db/migration/$arq" ]; then
    die "Arquivo antigo ainda presente em src: $mod/$arq (renomeacao incompleta)."
  fi
done
ok "Nenhum nome antigo sobrou em src/"

# -----------------------------------------------------------------------------
say "==> 2) Checagem REAL de versoes Flyway (somente */src/main/resources)"
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
: > "$TMP"
while IFS= read -r f; do
  base=$(basename "$f")
  v=$(printf '%s' "$base" | sed -n -E 's/^V([0-9]+)__.*/\1/p')
  if [ -z "$v" ]; then
    warn "Arquivo fora do padrao V<n>__nome.sql (ignorado na checagem): $f"
    continue
  fi
  printf '%s\t%s\n' "$v" "$f" >> "$TMP"
done < <(find . -type f -path '*/src/main/resources/db/migration/*.sql' -not -path './.git/*')

DUPS=$(cut -f1 "$TMP" | sort -n | uniq -d)
if [ -n "$DUPS" ]; then
  say "DUPLICIDADES REAIS encontradas (resolva antes de compilar):"
  while IFS= read -r v; do
    awk -F'\t' -v x="$v" '$1==x{print "       " $2}' "$TMP"
  done <<< "$DUPS"
  die "Existem versoes Flyway duplicadas de verdade (lista acima)."
fi
ok "Nenhuma duplicidade real em src/ (as do log anterior eram copias em target/classes)"

# -----------------------------------------------------------------------------
say "==> 3) Compilando raiji-deliveries + dependencias (gate antes do commit)"
if mvn -B -pl raiji-deliveries -am clean install -DskipTests 2>&1 | tee "$LOG_DIR/build-modulo.log"; then
  ok "Compilacao do modulo OK (log: $LOG_DIR/build-modulo.log)"
else
  die "Compilacao falhou. Me envie o conteudo de $LOG_DIR/build-modulo.log"
fi

# -----------------------------------------------------------------------------
say "==> 4) Commit"
for ign in ".fix-merge-logs/" ".fix-merge-dumps/" "saida-diagnostico.txt" "dumps-fix.tgz"; do
  grep -qxF "$ign" .gitignore 2>/dev/null || printf '%s\n' "$ign" >> .gitignore
done

if [ -z "$(git status --porcelain)" ]; then
  warn "Nada a commitar (arvore limpa)."
else
  git add -A
  git commit -m "feat: integra pagamentos de deliveries ao modelo novo da develop

- Delivery (com.raiji.deliveries) implementa Payable (raiji-rides.payment):
  paymentIntentId, paymentStatus, paidAmountCents, paidAt, couponId, discountCents
- DeliveryRepository.findByPaymentIntentId
- DeliveryPaymentService, DeliveryRefunds e DeliveryPaymentController
  portados de feature/integracao-parte-2 para o modelo da develop
- Estorno no cancelamento e no fechamento (DeliveryService)
- paymentStatus/paidAt expostos no DeliveryResponse
- Migracoes da feature renumeradas contra colisao Flyway com a develop:
  rides V22->V27, V24->V28, V25->V29, V26->V30; deliveries V23->V31 (idempotente)
- @MockBean defensivo de DeliveryPaymentService no DeliveryControllerTest" \
    || die "git commit falhou."
  ok "Commit criado: $(git rev-parse --short HEAD)"
fi

# -----------------------------------------------------------------------------
say "==> 5) Build completo do monolito (mvn clean install)"
if mvn -B clean install -DskipTests 2>&1 | tee "$LOG_DIR/build-full.log"; then
  ok "Build completo OK (log: $LOG_DIR/build-full.log)"
else
  die "Build completo falhou. Me envie $LOG_DIR/build-full.log (o commit ja esta feito; nada se perdeu)."
fi

# -----------------------------------------------------------------------------
say ""
say "=============================================================="
say " INTEGRACAO CONCLUIDA COM SUCESSO"
say "=============================================================="
say " Proximos passos:"
say "   1) git push -u origin feature/integracao-completa"
say "   2) Para rodar: use um banco de dev LIMPO (as migracoes da"
say "      feature foram renumeradas). Se um banco antigo ja aplicou"
say "      V22/V24/V25/V26 (rides) ou V23 (deliveries) da feature,"
say "      rode flyway repair ou recrie o schema."
say "   3) Docker: docker build -f <seu.Dockerfile> ."
say " Rollback de emergencia (volta TUDO ao estado do merge):"
say "   git reset --hard $BACKUP_REF"
