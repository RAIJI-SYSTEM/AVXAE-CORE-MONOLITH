-- Repasse manual ao motorista.
--
-- O passageiro/remetente paga à plataforma (Stripe). Depois da viagem concluída (ou da
-- encomenda entregue), a parte do motorista (valor pago - comissão) fica "a repassar".
-- A plataforma faz o Pix por fora e registra o repasse aqui: os itens pagos passam a
-- apontar para ele (payout_id), e não entram de novo no saldo.

CREATE TABLE IF NOT EXISTS driver_payouts (
    id            UUID PRIMARY KEY,
    driver_sub    VARCHAR(64)  NOT NULL,
    amount_cents  BIGINT       NOT NULL CHECK (amount_cents > 0),
    item_count    INTEGER      NOT NULL CHECK (item_count > 0),
    pix_key       VARCHAR(140),
    note          VARCHAR(255),
    paid_by       VARCHAR(64)  NOT NULL,
    paid_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_driver_payouts_driver ON driver_payouts(driver_sub, paid_at DESC);

-- Chave Pix que o motorista informa para receber
CREATE TABLE IF NOT EXISTS driver_pix_keys (
    driver_sub  VARCHAR(64)  PRIMARY KEY,
    pix_key     VARCHAR(140) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE ride_passengers ADD COLUMN IF NOT EXISTS payout_id UUID REFERENCES driver_payouts(id);
ALTER TABLE deliveries      ADD COLUMN IF NOT EXISTS payout_id UUID REFERENCES driver_payouts(id);
CREATE INDEX IF NOT EXISTS idx_ride_passengers_unpaid_out ON ride_passengers(payment_status) WHERE payout_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_deliveries_unpaid_out      ON deliveries(payment_status)      WHERE payout_id IS NULL;
