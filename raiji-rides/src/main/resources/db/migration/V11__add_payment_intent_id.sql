-- S2-05 / RN04 — payment_intent_id da reserva (carona paga).
--
-- Preenchido no aceite (PATCH ACCEPT) quando informado no payload; usado no
-- cancelamento para disparar o estorno automático no Stripe. NULL permitido
-- (caronas gratuitas e reservas antigas não têm pagamento associado).

ALTER TABLE ride_passengers
    ADD COLUMN IF NOT EXISTS payment_intent_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_ride_pass_payment_intent
    ON ride_passengers(payment_intent_id);
