#!/usr/bin/env bash
# =============================================================================
# corrigir-payable.sh
#
# Corrige o ultimo erro de compilacao:
#   Delivery is not abstract and does not override abstract method
#   setCouponId(java.util.UUID) in com.raiji.rides.payment.Payable
#
# Causa: o patch de pagamentos foi montado a partir de um dump grep da
# entidade ANTIGA da feature que filtrava linhas com "coupon" (por isso o
# dump tinha a lacuna entre as linhas 189 e 192). 5 dos 6 pares de membros
# do Payable foram copiados; couponId (campo + getCouponId/setCouponId)
# ficou de fora. getId() nao precisa de override: vem herdado de
# AuditableEntity (na entidade antiga tambem nao havia override de getId).
#
# Este script:
#   1) Completa couponId/getCouponId/setCouponId em Delivery.java (idempotente)
#   2) Garante as colunas de pagamento na migracao V31 (idempotente,
#      inclusive coupon_id uuid — a Hibernate vai validar o schema)
#   3) Compila raiji-deliveries + dependencias (gate antes do commit)
#   4) Commit de tudo
#   5) Build completo do monolito
#   6) Proximos passos (push, banco, Docker)
#
# Rollback de emergencia: git reset --hard backup/antes-fix-deliveries
#
# Modo de teste (só passos 1 e 2, sem git/mvn), usado para validar a logica:
#   TEST_FIX_ONLY=1 PAYABLE=<arq> DELIVERY=<arq> V31=<arq> bash corrigir-payable.sh
# =============================================================================
set -euo pipefail

PAYABLE="${PAYABLE:-raiji-rides/src/main/java/com/raiji/rides/payment/Payable.java}"
DELIVERY="${DELIVERY:-raiji-deliveries/src/main/java/com/raiji/deliveries/Delivery.java}"
V31="${V31:-raiji-deliveries/src/main/resources/db/migration/V31__delivery_payment_status.sql}"
TEST_FIX_ONLY="${TEST_FIX_ONLY:-0}"

BRANCH_ESPERADA="feature/integracao-completa"
HEAD_ESPERADO="2a6f680"
BACKUP_REF="backup/antes-fix-deliveries"
LOG_DIR=".fix-merge-logs"

say(){ printf '%s\n' "$*"; }
ok(){ printf '  OK %s\n' "$*"; }
warn(){ printf 'AVISO %s\n' "$*"; }
die(){ printf 'ERRO %s\n' "$*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# Passo 1: completa couponId/getCouponId/setCouponId em Delivery.java
# -----------------------------------------------------------------------------
fix_delivery(){
  if ! grep -q "import java.util.UUID;" "$DELIVERY"; then
    if grep -q "^import " "$DELIVERY"; then
      awk 'BEGIN{ins=0} !ins && /^import /{ print "import java.util.UUID;"; ins=1 } {print}' "$DELIVERY" > "$DELIVERY.tmp" \
        && mv "$DELIVERY.tmp" "$DELIVERY"
    else
      sed -i '/^package /a import java.util.UUID;' "$DELIVERY"
    fi
    ok "import java.util.UUID adicionado em Delivery.java"
  else
    ok "import java.util.UUID ja presente em Delivery.java"
  fi

  ADD_FIELD=0; ADD_GET=0; ADD_SET=0
  grep -qE "private[[:space:]]+[A-Za-z0-9_.<>]+[[:space:]]+couponId[[:space:]]*;" "$DELIVERY" || ADD_FIELD=1
  grep -q "getCouponId" "$DELIVERY" || ADD_GET=1
  grep -q "setCouponId" "$DELIVERY" || ADD_SET=1

  if [ "$ADD_FIELD" = "1" ] || [ "$ADD_GET" = "1" ] || [ "$ADD_SET" = "1" ]; then
    BLOCO=""
    if [ "$ADD_FIELD" = "1" ]; then
      BLOCO="    /** Cupom aplicado (null = sem cupom) */
    private UUID couponId;"
    fi
    if [ "$ADD_GET" = "1" ]; then
      BLOCO="$BLOCO
    @Override public UUID getCouponId() { return couponId; }"
    fi
    if [ "$ADD_SET" = "1" ]; then
      BLOCO="$BLOCO
    @Override public void setCouponId(UUID v) { this.couponId = v; }"
    fi

    export FIX_BLOCO="$BLOCO"
    awk '
      { linhas[NR] = $0 }
      END {
        ult = NR
        while (ult > 0 && linhas[ult] !~ /^[}][ \t]*$/) ult--
        if (ult == 0) { print "ERRO interno: chave final nao encontrada" > "/dev/stderr"; exit 1 }
        for (i = 1; i < ult; i++) print linhas[i]
        print ""
        print ENVIRON["FIX_BLOCO"]
        for (i = ult; i <= NR; i++) print linhas[i]
      }' "$DELIVERY" > "$DELIVERY.tmp" && mv "$DELIVERY.tmp" "$DELIVERY"
    unset FIX_BLOCO

    say "-- Adicionado em Delivery.java:"
    printf '%s\n' "$BLOCO" | sed 's/^/     /'
  else
    ok "couponId/getCouponId/setCouponId ja presentes — nada a inserir"
  fi

  say "-- Verificacao (linhas com couponId):"
  grep -nm 10 "couponId" "$DELIVERY" | sed 's/^/     /'
}

# -----------------------------------------------------------------------------
# Passo 2: garante colunas de pagamento na migracao V31 (idempotente)
# -----------------------------------------------------------------------------
fix_v31(){
  if [ ! -f "$V31" ]; then
    warn "Migracao V31 nao encontrada em: $V31 (pulando checagem de colunas)"
    return 0
  fi
  TABELA=$(grep -oiE 'ALTER[[:space:]]+TABLE[[:space:]]+(IF[[:space:]]+NOT[[:space:]]+EXISTS[[:space:]]+)?[a-zA-Z_]+' "$V31" | head -n1 | awk '{print $NF}')
  if [ -z "$TABELA" ]; then
    TABELA="deliveries"
    warn "Nao achei ALTER TABLE na V31; assumindo tabela '$TABELA'."
  else
    ok "Tabela alvo detectada na V31: $TABELA"
  fi

  ADDS=""
  for par in "payment_intent_id|varchar" "payment_status|varchar" "paid_amount_cents|bigint" "paid_at|timestamptz" "discount_cents|bigint" "coupon_id|uuid"; do
    col="${par%%|*}"; typ="${par#*|}"
    if ! grep -qi "$col" "$V31"; then
      ADDS="$ADDS
ALTER TABLE $TABELA ADD COLUMN IF NOT EXISTS $col $typ;"
      ok "Coluna ausente sera adicionada: $col ($typ)"
    fi
  done
  if [ -n "$ADDS" ]; then
    printf '%s\n' "$ADDS" >> "$V31"
    ok "V31 atualizada (ALTERs idempotentes ao final)"
  else
    ok "V31 ja cobre todas as colunas de pagamento"
  fi
}

# -----------------------------------------------------------------------------
if [ "$TEST_FIX_ONLY" = "1" ]; then
  say "== MODO TESTE: aplicando somente os passos 1 e 2 =="
  fix_delivery
  fix_v31
  exit 0
fi

# -----------------------------------------------------------------------------
say "==> 0) Pre-checagens"
[ -d raiji-deliveries ] || die "Rode este script na raiz do repositorio."
[ -f "$PAYABLE" ] || die "Payable nao encontrado: $PAYABLE"
[ -f "$DELIVERY" ] || die "Delivery nao encontrada: $DELIVERY"
command -v mvn >/dev/null 2>&1 || die "mvn nao encontrado no PATH."

BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$BRANCH" = "$BRANCH_ESPERADA" ] || die "Branch inesperada '$BRANCH' (esperada: $BRANCH_ESPERADA)."
ok "Branch: $BRANCH"

HEAD_SHORT=$(git rev-parse --short HEAD)
if [ "$HEAD_SHORT" = "$HEAD_ESPERADO" ]; then
  ok "HEAD = $HEAD_SHORT (sem commit — patches do fix-merge-final.sh intactos)"
else
  warn "HEAD = $HEAD_SHORT (esperado $HEAD_ESPERADO). Prosseguindo mesmo assim."
fi
git rev-parse --verify --quiet "$BACKUP_REF" >/dev/null || die "Backup '$BACKUP_REF' sumiu — sem rede de seguranca."
ok "Backup disponivel: $BACKUP_REF ($(git rev-parse --short "$BACKUP_REF"))"

mkdir -p "$LOG_DIR"

# -----------------------------------------------------------------------------
say "==> 1) Completando Payable em Delivery.java (cupom)"
fix_delivery

# -----------------------------------------------------------------------------
say "==> 2) Garantindo colunas de pagamento na migracao V31"
fix_v31

# -----------------------------------------------------------------------------
say "==> 3) Compilando raiji-deliveries + dependencias (gate antes do commit)"
if mvn -B -pl raiji-deliveries -am clean install -DskipTests 2>&1 | tee "$LOG_DIR/build-modulo-2.log"; then
  ok "Compilacao do modulo OK (log: $LOG_DIR/build-modulo-2.log)"
else
  die "Compilacao falhou. Me envie o conteudo de $LOG_DIR/build-modulo-2.log"
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
  paymentIntentId, paymentStatus, paidAmountCents, paidAt, discountCents
  e cupom (couponId + getCouponId/setCouponId)
- DeliveryRepository.findByPaymentIntentId
- DeliveryPaymentService, DeliveryRefunds e DeliveryPaymentController
  portados de feature/integracao-parte-2 para o modelo da develop
- Estorno no cancelamento e no fechamento (DeliveryService)
- paymentStatus/paidAt expostos no DeliveryResponse
- Migracoes da feature renumeradas contra colisao Flyway com a develop:
  rides V22->V27, V24->V28, V25->V29, V26->V30; deliveries V23->V31
  (idempotente, com todas as colunas de pagamento inclusive coupon_id)
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
