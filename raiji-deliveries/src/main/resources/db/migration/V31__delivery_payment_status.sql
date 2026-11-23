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

ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS coupon_id uuid;
