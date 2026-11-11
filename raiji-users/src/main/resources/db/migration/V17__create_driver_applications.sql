-- V15: pedidos para virar motorista (verificação da CNH via Infosimples).
-- O código de segurança da CNH não é gravado; a foto fica no armazenamento
-- (S3/RustFS) só até a decisão.
CREATE TABLE IF NOT EXISTS driver_applications (
    id               UUID PRIMARY KEY,
    sub              VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    account_name     VARCHAR(200),
    image_key        VARCHAR(200),
    cnh_name         VARCHAR(200),
    cpf              VARCHAR(11),
    registro         VARCHAR(20),
    categoria        VARCHAR(10),
    validade         DATE,
    reason           VARCHAR(500),
    ocr_code         INTEGER,
    validation_code  INTEGER,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    decided_at       TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_driver_applications_sub_created
    ON driver_applications (sub, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_driver_applications_sub_status
    ON driver_applications (sub, status);
