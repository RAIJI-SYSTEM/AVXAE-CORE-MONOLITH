-- S2-04 / RF09 + RN06 — cancelamento de reserva pelo passageiro.
--
-- A coluna cancellation_reason já existe desde o V2 (reutilizada pelo REJECT
-- da S2-02). Aqui acrescentamos apenas o MOMENTO do cancelamento, para
-- satisfazer o critério de aceite "reserva muda para CANCELLED com
-- cancellationReason e data".
--
-- Idempotente: safe para reexecução (ADD COLUMN IF NOT EXISTS).

ALTER TABLE ride_passengers
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITH TIME ZONE;
