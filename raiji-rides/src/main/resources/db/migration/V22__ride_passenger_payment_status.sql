-- Pagamento da reserva (passageiro paga depois que o motorista aceita).
--
-- payment_status: UNPAID | PROCESSING (Pix aguardando) | PAID | REFUNDED
-- O backend só marca PAID depois de conferir o PaymentIntent no Stripe.
-- paid_amount_cents: valor efetivamente recebido (usado no estorno).

ALTER TABLE ride_passengers
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN IF NOT EXISTS paid_amount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

-- Reservas antigas com PaymentIntent informado no aceite (fluxo S2-05) eram pagas
UPDATE ride_passengers
   SET payment_status = 'PAID'
 WHERE payment_intent_id IS NOT NULL;

ALTER TABLE ride_passengers
    ADD CONSTRAINT chk_ride_pass_payment_status
        CHECK (payment_status IN ('UNPAID', 'PROCESSING', 'PAID', 'REFUNDED'));
