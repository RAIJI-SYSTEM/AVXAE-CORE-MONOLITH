#!/usr/bin/env bash
###############################################################################
# fix-merge-final.sh
#
# PORT COMPLETO: pagamento/estorno de encomendas da feature/integracao-parte-2
# para o modelo NOVO da develop (entidade com.raiji.deliveries.Delivery).
#
# O que ele faz:
#   1. Reseta a branch para o estado do merge (backup/antes-fix-deliveries = 2a6f680)
#   2. Aplica patches ancorados (atomicos: se qualquer ancora falhar, NADA muda):
#        - Delivery.java: implements Payable + campos paymentStatus/paidAmountCents/
#          paidAt/discountCents + getters/setters
#        - DeliveryRepository.java: findByPaymentIntentId
#        - DeliveryService.java: estorno no cancel (refundIfPaid)
#        - DeliveryRideBookings.java: estorno no cancelamento de carona
#        - DeliveryResponse.java: expoe paymentStatus/paidAt
#        - DeliveryControllerTest: @MockBean defensivo de DeliveryPaymentService
#   3. Cria/rewrite: DeliveryPaymentService (port), DeliveryRefunds (port),
#      DeliveryPaymentController (endpoints POST/GET /api/deliveries/{id}/payment)
#   4. Renumera migracoes da feature (V27..V31) para nao colidir com V22..V26 da develop
#   5. Compila (gate), roda os testes do modulo (gate), commita, build completo (gate)
#
# Uso:  bash fix-merge-final.sh
# Undo: git reset --hard backup/antes-fix-deliveries
###############################################################################
set -uo pipefail

DEV=origin/develop
FEAT=origin/feature/integracao-parte-2
BACKUP_BRANCH="backup/antes-fix-deliveries"
BRANCH_ESPERADA="feature/integracao-completa"
LOG_DIR=".fix-merge-logs"
mkdir -p "$LOG_DIR"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  OK\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mAVISO\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERRO\033[0m %s\n' "$*" >&2; exit 1; }

###############################################################################
say "0) Verificacoes iniciais"
###############################################################################
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "Rode dentro do repositorio."
cd "$(git rev-parse --show-toplevel)" || die "Nao foi possivel entrar na raiz."

RAMO="$(git branch --show-current)"
if [ "$RAMO" != "$BRANCH_ESPERADA" ] && [ "${FORCE:-0}" != "1" ]; then
  die "Voce esta na branch '$RAMO'; esperado '$BRANCH_ESPERADA' (ou FORCE=1)."
fi
ok "Branch: $RAMO"

git rev-parse -q --verify "$FEAT^{commit}" >/dev/null || die "$FEAT nao existe (git fetch origin)."
git rev-parse -q --verify "$BACKUP_BRANCH" >/dev/null 2>&1 \
  || die "Backup '$BACKUP_BRANCH' nao existe. Este script volta a esse ponto de partida."

if ! git diff --quiet || ! git diff --cached --quiet; then
  warn "Arvore suja — vou resetar para $BACKUP_BRANCH (estado do merge)."
fi

say "Voltando ao estado do merge (ponto de partida limpo)"
git reset --hard "$BACKUP_BRANCH" >/dev/null || die "Falha no reset para $BACKUP_BRANCH."
ok "HEAD = $(git rev-parse --short HEAD) ($(git log -1 --format=%s))"

MVN="mvn"; [ -x ./mvnw ] && MVN="./mvnw"

###############################################################################
say "1) Contrato de pagamento (referencias para conferencia no log)"
###############################################################################
echo "--- Payable.java (arvore atual, modulo raiji-rides):"
cat -n raiji-rides/src/main/java/com/raiji/rides/payment/Payable.java 2>/dev/null \
  || warn "Payable.java NAO esta na arvore!"
echo
echo "--- Membros de pagamento da entidade ANTIGA da feature (domain/Delivery.java):"
git show "$FEAT:raiji-deliveries/src/main/java/com/raiji/deliveries/domain/Delivery.java" 2>/dev/null \
  | grep -nE "Payable|PaymentStatus|paymentStatus|paymentIntentId|paidAmount|paidAt|discountCents|class Delivery" || true

###############################################################################
say "2) Aplicando patches ancorados (atomicos)"
###############################################################################
python3 - <<'PYEOF'
import re, sys
from pathlib import Path

problems = []

def load(p):
    return Path(p).read_text(encoding='utf-8', errors='surrogateescape')

def save(p, c):
    Path(p).write_text(c, encoding='utf-8', errors='surrogateescape')
    print(f"  [PATCH] salvo: {p}")

def rep(c, old, new, label, path):
    n = c.count(old)
    if n != 1:
        problems.append(f"{path}: ancora '{label}' encontrada {n}x (esperado 1)")
        return c
    print(f"  [PATCH] {label}")
    return c.replace(old, new, 1)

# ---------- 1) Entidade Delivery (develop) ----------
P = 'raiji-deliveries/src/main/java/com/raiji/deliveries/Delivery.java'
c = load(P)
c = rep(c, 'public class Delivery extends AuditableEntity {',
        'public class Delivery extends AuditableEntity implements Payable {',
        'implements Payable', P)
c = rep(c, 'import com.raiji.common.enums.DeliveryStatus;',
        'import com.raiji.common.enums.DeliveryStatus;\n'
        'import com.raiji.common.enums.PaymentStatus;\n'
        'import com.raiji.rides.payment.Payable;',
        'imports PaymentStatus/Payable', P)
c = rep(c, '    @Column(name = "payment_intent_id", length = 100)\n    private String paymentIntentId;\n',
        '    @Column(name = "payment_intent_id", length = 100)\n    private String paymentIntentId;\n\n'
        '    @Enumerated(EnumType.STRING)\n    @Column(name = "payment_status", nullable = false, length = 20)\n'
        '    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;\n\n'
        '    @Column(name = "paid_amount_cents")\n    private Long paidAmountCents;\n\n'
        '    @Column(name = "paid_at")\n    private Instant paidAt;\n\n'
        '    @Column(name = "discount_cents")\n    private Long discountCents;\n',
        'campos de pagamento', P)
s = c.rstrip('\n')
if s.endswith('}'):
    GETTERS = (
        '\n    // ---------------------------------------------------------\n'
        '    // Pagamento (portado da feature/integracao-parte-2)\n'
        '    // ---------------------------------------------------------\n\n'
        '    public PaymentStatus getPaymentStatus() {\n        return paymentStatus;\n    }\n\n'
        '    public void setPaymentStatus(PaymentStatus v) {\n        this.paymentStatus = v;\n    }\n\n'
        '    public Long getPaidAmountCents() {\n        return paidAmountCents;\n    }\n\n'
        '    public void setPaidAmountCents(Long v) {\n        this.paidAmountCents = v;\n    }\n\n'
        '    public Instant getPaidAt() {\n        return paidAt;\n    }\n\n'
        '    public void setPaidAt(Instant v) {\n        this.paidAt = v;\n    }\n\n'
        '    public Long getDiscountCents() {\n        return discountCents;\n    }\n\n'
        '    public void setDiscountCents(Long v) {\n        this.discountCents = v;\n    }\n'
    )
    c = s[:-1] + GETTERS + '}\n'
    print('  [PATCH] getters/setters de pagamento')
else:
    problems.append(P + ': fim de arquivo inesperado')
FILES = {P: c}

# ---------- 2) DeliveryRepository ----------
P = 'raiji-deliveries/src/main/java/com/raiji/deliveries/repository/DeliveryRepository.java'
c = load(P)
s = c.rstrip('\n')
if s.endswith('}'):
    c = s[:-1] + (
        '\n    /** Encomenda dona da cobrança (webhook do Stripe). */\n'
        '    Optional<Delivery> findByPaymentIntentId(String paymentIntentId);\n'
    ) + '}\n'
    print('  [PATCH] findByPaymentIntentId no repositorio')
else:
    problems.append(P + ': fim de arquivo inesperado')
FILES[P] = c

# ---------- 3) DeliveryResponse ----------
P = 'raiji-deliveries/src/main/java/com/raiji/deliveries/dto/DeliveryResponse.java'
c = load(P)
c = rep(c, 'import com.raiji.common.enums.DeliveryStatus;',
        'import com.raiji.common.enums.DeliveryStatus;\nimport com.raiji.common.enums.PaymentStatus;',
        'import PaymentStatus', P)
c = rep(c, 'import com.raiji.rides.pricing.PriceBreakdown;',
        'import com.raiji.rides.pricing.PriceBreakdown;\nimport com.raiji.rides.payment.PaymentService;',
        'import PaymentService', P)
c = rep(c, '        RideSummary ride\n) {',
        '        RideSummary ride,\n        PaymentStatus paymentStatus,\n        Instant paidAt\n) {',
        'campos paymentStatus/paidAt no record', P)
c = rep(c, '                RideSummary.of(ride)\n        );',
        '                RideSummary.of(ride),\n'
        '                PaymentService.statusOf(d, PaymentService.toCents(d.getPrice())),\n'
        '                d.getPaidAt()\n        );',
        'populacao paymentStatus/paidAt em of()', P)
FILES[P] = c

# ---------- 4) DeliveryService: estorno no cancel ----------
P = 'raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryService.java'
c = load(P)
c = rep(c, 'package com.raiji.deliveries.service;',
        'package com.raiji.deliveries.service;\n\nimport org.springframework.beans.factory.annotation.Autowired;',
        'import Autowired', P)
c = rep(c, '    boolean podeVerComoRemetente(Delivery d, String sub) {',
        '    @Autowired(required = false)\n    private DeliveryRefunds refunds;\n\n'
        '    boolean podeVerComoRemetente(Delivery d, String sub) {',
        'campo refunds', P)
c = rep(c, '        d.cancel(motivo);',
        '        d.cancel(motivo);\n'
        '        if (refunds != null) {\n'
        '            refunds.refundIfPaid(d, actorSub); // cancelou depois de pagar: devolve o valor (porta da feature)\n'
        '        }',
        'estorno no cancel', P)
FILES[P] = c

# ---------- 5) DeliveryRideBookings: estorno no cancelamento de carona ----------
P = 'raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryRideBookings.java'
c = load(P)
c = rep(c, 'import org.springframework.stereotype.Component;',
        'import org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.stereotype.Component;',
        'import Autowired', P)
c = rep(c, '    private final DeliveryNotifier notifier;',
        '    @Autowired(required = false)\n    private DeliveryRefunds refunds;\n\n'
        '    private final DeliveryNotifier notifier;',
        'campo refunds', P)
c = rep(c, '            d.cancel(reason);',
        '            d.cancel(reason);\n'
        '            if (refunds != null) {\n'
        '                refunds.refundIfPaid(d, null); // viagem cancelada: devolve o que ja foi pago (porta da feature)\n'
        '            }',
        'estorno no closeAll', P)
FILES[P] = c

# ---------- 6) DeliveryControllerTest: @MockBean defensivo (best-effort) ----------
P = 'raiji-deliveries/src/test/java/com/raiji/deliveries/web/DeliveryControllerTest.java'
try:
    c = load(P)
    m = re.search(r'class DeliveryControllerTest[^{]*\{', c)
    if m and 'DeliveryPaymentService' not in c:
        c = c[:m.end()] + (
            '\n\n    @org.springframework.boot.test.mock.mockito.MockBean\n'
            '    private com.raiji.deliveries.service.DeliveryPaymentService deliveryPaymentService;\n'
        ) + c[m.end():]
        FILES[P] = c
        print('  [PATCH] @MockBean defensivo no DeliveryControllerTest')
    elif 'DeliveryPaymentService' in c:
        print('  [PATCH] DeliveryControllerTest ja referencia DeliveryPaymentService (nada a fazer)')
    else:
        print('  [AVISO] anchor do DeliveryControllerTest nao encontrado (seguindo sem patch)')
except FileNotFoundError:
    print('  [AVISO] DeliveryControllerTest nao encontrado (seguindo sem patch)')

if problems:
    print()
    for p_ in problems:
        print(f'  [ERRO] {p_}')
    sys.exit(2)

for p_, c_ in FILES.items():
    save(p_, c_)
print('  TODOS os patches aplicados com sucesso.')
PYEOF
[ $? -eq 0 ] || die "Patches falharam (nada foi alterado). Me envie a saida acima."

###############################################################################
say "3) Gravando arquivos portados/criados"
###############################################################################
MOD=raiji-deliveries/src/main/java/com/raiji/deliveries

cat > "$MOD/service/DeliveryPaymentService.java" <<'JAVAEOF'
package com.raiji.deliveries.service;

import java.util.Map;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.payment.PaymentIntentChanged;
import com.raiji.rides.payment.PaymentResponse;
import com.raiji.rides.payment.PaymentService;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.rewards.CouponChoice;
import com.raiji.rides.rewards.CouponService;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pagamento da encomenda: o remetente (ou alguém da empresa dele) paga depois que o
 * motorista aceita (CONFIRMED). Se cancelar depois de pago, o valor é estornado
 * ({@link DeliveryRefunds}).
 */
@Service
public class DeliveryPaymentService {

    private final DeliveryRepository deliveries;
    private final RideRepository rides;
    private final DeliveryService deliveryService;
    private final PaymentService payments;
    private final DeliveryNotifier notifier;
    private final CouponService coupons;

    public DeliveryPaymentService(DeliveryRepository deliveries, RideRepository rides, DeliveryService deliveryService,
                                  PaymentService payments, DeliveryNotifier notifier, CouponService coupons) {
        this.coupons = coupons;
        this.deliveries = deliveries;
        this.rides = rides;
        this.deliveryService = deliveryService;
        this.payments = payments;
        this.notifier = notifier;
    }

    @Transactional
    public PaymentResponse start(UUID deliveryId, String payerSub, CouponChoice coupon) {
        Delivery d = encomenda(deliveryId);
        Ride ride = rides.findById(d.getRideId()).orElseThrow(this::naoEncontrada);
        if (!deliveryService.podeVerComoRemetente(d, payerSub)) {
            throw naoEncontrada();
        }
        if (d.getStatus() == DeliveryStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O motorista ainda não aceitou esta encomenda.");
        }
        if (d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta encomenda não está aguardando pagamento.");
        }
        boolean pagaAntes = d.getPaymentStatus() == PaymentStatus.PAID;
        long amount = coupons.amountToCharge(d, payerSub, coupon, valor(d));
        PaymentResponse response = payments.start(d, amount,
                "Raiji · encomenda " + ride.getOriginCity() + " → " + ride.getDestinationCity(),
                Map.of("type", "DELIVERY", "deliveryId", deliveryId.toString(),
                        "rideId", ride.getId().toString(), "payerSub", payerSub),
                "delivery:" + deliveryId);
        deliveries.save(d);
        if (!pagaAntes && d.getPaymentStatus() == PaymentStatus.PAID) {
            avisarMotorista(ride, d);
        }
        return response.withCoupon(d);
    }

    /** Situação do pagamento, conferida no Stripe. Visível ao remetente (e empresa) e ao motorista. */
    @Transactional
    public PaymentResponse status(UUID deliveryId, String viewerSub) {
        Delivery d = encomenda(deliveryId);
        Ride ride = rides.findById(d.getRideId()).orElseThrow(this::naoEncontrada);
        if (!deliveryService.podeVerComoRemetente(d, viewerSub) && !ride.getDriverSub().equals(viewerSub)) {
            throw naoEncontrada();
        }
        if (payments.refresh(d)) {
            deliveries.save(d);
            avisarMotorista(ride, d);
        }
        return payments.view(d, valor(d) - (d.getDiscountCents() == null ? 0 : d.getDiscountCents())).withCoupon(d);
    }

    /** Webhook do Stripe: atualiza a encomenda dona da cobrança (se houver). */
    @EventListener
    @Transactional
    public void onPaymentIntentChanged(PaymentIntentChanged event) {
        deliveries.findByPaymentIntentId(event.intent().id()).ifPresent(d -> {
            boolean pagaAgora = payments.apply(d, event.intent());
            deliveries.save(d);
            if (pagaAgora) {
                rides.findById(d.getRideId()).ifPresent(ride -> avisarMotorista(ride, d));
            }
        });
    }

    static long valor(Delivery d) {
        return PaymentService.toCents(d.getPrice());
    }

    private void avisarMotorista(Ride ride, Delivery d) {
        notifier.driver(ride.getDriverSub(), "DELIVERY_PAYMENT_RECEIVED", ride.getId(), d.getId(),
                (d.getSenderName() == null ? "O remetente" : d.getSenderName())
                        + " pagou a encomenda que vai na sua carona para " + ride.getDestinationCity() + ".");
    }

    private Delivery encomenda(UUID id) {
        return deliveries.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrada);
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Encomenda não encontrada.");
    }
}
JAVAEOF
ok "DeliveryPaymentService.java (portado para com.raiji.deliveries.Delivery)"

cat > "$MOD/service/DeliveryRefunds.java" <<'JAVAEOF'
package com.raiji.deliveries.service;

import com.raiji.deliveries.Delivery;
import com.raiji.rides.payment.PaymentService;
import com.raiji.rides.service.RideRefundService;

import org.springframework.stereotype.Component;

/** Estorno de encomenda cancelada depois de paga (mesma regra e auditoria das reservas). */
@Component
public class DeliveryRefunds {

    /** Tipo de entidade gravado na auditoria dos estornos. */
    public static final String ENTITY_TYPE = "DELIVERY";

    private final RideRefundService refunds;

    public DeliveryRefunds(RideRefundService refunds) {
        this.refunds = refunds;
    }

    /** Estorna se estava paga (e marca REFUNDED). Nunca lança exceção. */
    public void refundIfPaid(Delivery d, String actorSub) {
        refunds.refundIfPaid(d, ENTITY_TYPE, d.getId(), actorSub, PaymentService.toCents(d.getPrice()),
                "rideId=" + d.getRideId());
    }
}
JAVAEOF
ok "DeliveryRefunds.java (portado)"

cat > "$MOD/web/DeliveryPaymentController.java" <<'JAVAEOF'
package com.raiji.deliveries.web;

import java.util.UUID;

import com.raiji.deliveries.service.DeliveryPaymentService;
import com.raiji.rides.payment.PaymentResponse;
import com.raiji.rides.rewards.CouponChoice;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de pagamento da encomenda (portados da feature/integracao-parte-2).
 *
 * POST /api/deliveries/{id}/payment   remetente inicia o pagamento (após o aceite)
 * GET  /api/deliveries/{id}/payment   situação do pagamento (remetente ou motorista)
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class DeliveryPaymentController {

    private final DeliveryPaymentService payments;

    public DeliveryPaymentController(DeliveryPaymentService payments) {
        this.payments = payments;
    }

    /** Corpo opcional: {"coupon": "<id do cupom>"} ou {"coupon": "none"}; sem corpo mantém o cupom atual */
    @PostMapping("/api/deliveries/{id}/payment")
    public PaymentResponse startPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @RequestBody(required = false) CouponChoice.Body body) {
        return payments.start(id, jwt.getSubject(), CouponChoice.from(body));
    }

    @GetMapping("/api/deliveries/{id}/payment")
    public PaymentResponse paymentStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.status(id, jwt.getSubject());
    }
}
JAVAEOF
ok "DeliveryPaymentController.java (novo, endpoints da feature)"

###############################################################################
say "4) Renumerando migracoes da feature (colisao de versao Flyway com a develop)"
###############################################################################
RIDERES=raiji-rides/src/main/resources/db/migration
DELRES=raiji-deliveries/src/main/resources/db/migration

echo "-- Duplicidades ANTES do renumero:"
find raiji-* -path '*/db/migration/*.sql' 2>/dev/null | sed -E 's|.*/(V[0-9]+)__.*|\1|' | sort | uniq -d | sed 's/^/     /' || true

git mv "$RIDERES/V22__ride_passenger_payment_status.sql" "$RIDERES/V27__ride_passenger_payment_status.sql" \
  && ok "rides  V22 -> V27__ride_passenger_payment_status.sql"
git mv "$RIDERES/V24__driver_payouts.sql"             "$RIDERES/V28__driver_payouts.sql" \
  && ok "rides  V24 -> V28__driver_payouts.sql"
git mv "$RIDERES/V25__push_devices.sql"               "$RIDERES/V29__push_devices.sql" \
  && ok "rides  V25 -> V29__push_devices.sql"
git mv "$RIDERES/V26__coupons_rewards.sql"            "$RIDERES/V30__coupons_rewards.sql" \
  && ok "rides  V26 -> V30__coupons_rewards.sql"
git mv "$DELRES/V23__delivery_payment_status.sql"     "$DELRES/V31__delivery_payment_status.sql" \
  && ok "deliv. V23 -> V31__delivery_payment_status.sql"

cat > "$DELRES/V31__delivery_payment_status.sql" <<'SQLEOF'
-- Pagamento da encomenda (remetente paga depois que o motorista aceita).
-- Portada da feature/integracao-parte-2 (era V23) e renumerada para nao colidir
-- com as migracoes V22..V26 da develop do modulo raiji-deliveries.
-- Mesmos estados das reservas de carona (V27).

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN IF NOT EXISTS paid_amount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS discount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS payment_intent_id VARCHAR(100);

ALTER TABLE deliveries
    ADD CONSTRAINT chk_delivery_payment_status
        CHECK (payment_status IN ('UNPAID', 'PROCESSING', 'PAID', 'REFUNDED'));

CREATE INDEX IF NOT EXISTS idx_deliveries_payment_intent ON deliveries(payment_intent_id);
SQLEOF
ok "V31__delivery_payment_status.sql reescrito (idempotente + discount_cents)"

echo "-- Duplicidades DEPOIS do renumero (deve ser vazio):"
DUPES="$(find raiji-* -path '*/db/migration/*.sql' 2>/dev/null | sed -E 's|.*/(V[0-9]+)__.*|\1|' | sort | uniq -d)"
if [ -n "$DUPES" ]; then
  echo "$DUPES" | while IFS= read -r v; do
    echo "     DUPLICADO: $v"
    find raiji-* -path '*/db/migration/*.sql' | grep -E "/${v}__" | sed 's/^/       /'
  done
  die "Ainda ha versoes de migracao duplicadas (lista acima). Me envie esta saida."
fi
ok "Nenhuma versao de migracao duplicada."

echo "-- Configuracao Flyway no application.yml (para conferencia):"
grep -rn -i "flyway" raiji-app/src/main/resources/ 2>/dev/null || echo "     (sem configuracao explicita — usara classpath:db/migration padrao)"

###############################################################################
say "5) Compilando raiji-deliveries + dependencias (sem testes)"
###############################################################################
CLOG="$LOG_DIR/final-compile.log"
if "$MVN" -B -pl raiji-deliveries -am clean install -DskipTests >"$CLOG" 2>&1; then
  ok "Compilacao PASSOU"
else
  warn "Compilacao falhou. Erros:"
  grep -E "^\[ERROR\]" "$CLOG" | head -40 || true
  echo
  warn "Referencias para eu corrigir na proxima rodada (cole junto com os erros):"
  echo "----- Payable.java (arvore):"
  cat raiji-rides/src/main/java/com/raiji/rides/payment/Payable.java 2>/dev/null || echo "(ausente)"
  echo "----- assinaturas publicas de PaymentService/CouponService/PaymentResponse:"
  grep -nE "public .*\(" \
    raiji-rides/src/main/java/com/raiji/rides/payment/PaymentService.java \
    raiji-rides/src/main/java/com/raiji/rides/payment/PaymentResponse.java \
    raiji-rides/src/main/java/com/raiji/rides/rewards/CouponService.java 2>/dev/null | head -60 || true
  warn "Para desfazer: git reset --hard $BACKUP_BRANCH"
  die "Pare no passo 5 (compilacao). Nada foi commitado."
fi

###############################################################################
say "6) Testes do modulo raiji-deliveries"
###############################################################################
TLOG="$LOG_DIR/final-tests.log"
if "$MVN" -B -pl raiji-deliveries test >"$TLOG" 2>&1; then
  ok "Testes do modulo PASSARAM"
  grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$TLOG" | tail -3 || true
else
  warn "Testes falharam. Resumo:"
  grep -E "^\[ERROR\]|Tests run:.*(Failures: [1-9]|Errors: [1-9])|BUILD FAILURE" "$TLOG" | head -40 || true
  warn "Para desfazer: git reset --hard $BACKUP_BRANCH"
  die "Pare no passo 6 (testes). Nada foi commitado."
fi

###############################################################################
say "7) Commit"
###############################################################################
git add \
  raiji-deliveries/src/main/java/com/raiji/deliveries/Delivery.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/repository/DeliveryRepository.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/dto/DeliveryResponse.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryService.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryRideBookings.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryPaymentService.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/service/DeliveryRefunds.java \
  raiji-deliveries/src/main/java/com/raiji/deliveries/web/DeliveryPaymentController.java \
  raiji-deliveries/src/main/resources/db/migration \
  raiji-deliveries/src/test/java/com/raiji/deliveries/web/DeliveryControllerTest.java \
  raiji-rides/src/main/resources/db/migration

git commit \
  -m "feat(deliveries): porta pagamento/estorno de encomendas da feature para o modelo novo da develop" \
  -m "- Entidade Delivery implementa Payable e ganha payment_status, paid_amount_cents, paid_at e discount_cents (migracao V31)" \
  -m "- DeliveryRepository ganha findByPaymentIntentId (webhook do Stripe)" \
  -m "- DeliveryPaymentService e DeliveryRefunds portados para com.raiji.deliveries.Delivery" \
  -m "- Novo DeliveryPaymentController com POST/GET /api/deliveries/{id}/payment" \
  -m "- Estorno reativado no cancelamento (DeliveryService.cancel e closeAll das caronas)" \
  -m "- DeliveryResponse expoe paymentStatus/paidAt" \
  -m "- Migracoes da feature renumeradas para V27..V31 (colidiam com V22..V26 da develop)" \
  || die "git commit falhou."
ok "Commit: $(git rev-parse --short HEAD)"

###############################################################################
say "8) Build completo (todos os modulos, com testes)"
###############################################################################
if [ "${SKIP_FULL:-0}" = "1" ]; then
  warn "SKIP_FULL=1 — build completo pulado. Rode 'mvn -B clean install' antes de publicar."
else
  FLOG="$LOG_DIR/final-full.log"
  if "$MVN" -B clean install >"$FLOG" 2>&1; then
    ok "BUILD SUCCESS — projeto inteiro compilado e testado"
    grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$FLOG" | tail -8 || true
  else
    warn "Build completo falhou. Erros:"
    grep -E "^\[ERROR\]|BUILD FAILURE" "$FLOG" | head -40 || true
    warn "O commit do passo 7 ja existe; para recomecar do zero: git reset --hard $BACKUP_BRANCH e rode o script de novo."
    echo "   Me envie: $FLOG"
    exit 1
  fi
fi

###############################################################################
say "RESUMO FINAL"
###############################################################################
echo "  * Pagamento/estorno de encomendas da feature portados para o modelo da develop"
echo "  * Endpoints POST/GET /api/deliveries/{id}/payment recriados"
echo "  * Migracoes renumeradas V27..V31 (sem duplicidade Flyway)"
echo "  * Backup do estado anterior: $BACKUP_BRANCH ($(git rev-parse --short "$BACKUP_BRANCH"))"
echo
say "Proximos passos"
echo "  git push -u origin $BRANCH_ESPERADA"
echo "  # Rode novamente o build Docker (RUN mvn -B clean package -DskipTests)"
echo "  # Se o banco de desenvolvimento ja tinha rodado migracoes V22..V26 de outra"
echo "  # versao dos arquivos, o Flyway pode reclamar de checksum: num ambiente de dev,"
echo "  # recrie o volume do banco (docker compose down -v && docker compose up) ou use flyway repair."
