-- S2-03 / RN12: peso declarado por item de bagagem.
-- Opcional (NULL permitido) para manter o contrato da S2-01 retrocompatível;
-- quando informado, deve ser > 0. A trava de capacidade soma
-- weight_kg × quantity das bagagens CONFIRMED (+ as da solicitação em aceite)
-- e compara com rides.cargo_max_weight_kg no PATCH de decisão.

ALTER TABLE baggage ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(10,2);

ALTER TABLE baggage DROP CONSTRAINT IF EXISTS chk_baggage_weight;
ALTER TABLE baggage ADD CONSTRAINT chk_baggage_weight
    CHECK (weight_kg IS NULL OR weight_kg > 0);
