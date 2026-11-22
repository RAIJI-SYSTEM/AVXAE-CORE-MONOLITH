-- Código de confirmação informado pelo destinatário.
ALTER TABLE deliveries
    ADD COLUMN confirmation_code VARCHAR(6);

-- Preenche códigos para entregas já existentes.
UPDATE deliveries
SET confirmation_code = LPAD(
    FLOOR(RANDOM() * 1000000)::BIGINT::TEXT,
    6,
    '0'
)
WHERE confirmation_code IS NULL;

ALTER TABLE deliveries
    ALTER COLUMN confirmation_code SET NOT NULL;

-- Tentativas de confirmação da entrega.
ALTER TABLE deliveries
    ADD COLUMN confirmation_attempts INTEGER NOT NULL DEFAULT 0;

-- Recompensa efetivamente registrada para o motorista.
ALTER TABLE deliveries
    ADD COLUMN reward_cents BIGINT;
