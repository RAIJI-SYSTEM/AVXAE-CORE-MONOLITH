-- Pagamento da encomenda (remetente paga depois que o motorista aceita).
-- Mesmos estados das reservas de carona (V22).

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN IF NOT EXISTS paid_amount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

ALTER TABLE deliveries
    ADD CONSTRAINT chk_delivery_payment_status
        CHECK (payment_status IN ('UNPAID', 'PROCESSING', 'PAID', 'REFUNDED'));

CREATE INDEX IF NOT EXISTS idx_deliveries_payment_intent ON deliveries(payment_intent_id);
