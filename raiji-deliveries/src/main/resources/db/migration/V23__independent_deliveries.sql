
-- Permite entregas que ainda não estão vinculadas a uma carona.
ALTER TABLE deliveries
    ALTER COLUMN ride_id DROP NOT NULL;

-- O novo fluxo informa as dimensões diretamente,
-- sem exigir uma categoria de pacote predefinida.
ALTER TABLE deliveries
    ALTER COLUMN package_size DROP NOT NULL;

-- Modalidade da entrega.
ALTER TABLE deliveries
    ADD COLUMN mode VARCHAR(20);

-- Origem.
ALTER TABLE deliveries
    ADD COLUMN origin_city VARCHAR(100),
    ADD COLUMN origin_state VARCHAR(2),
    ADD COLUMN origin_address VARCHAR(255);

-- Destino.
ALTER TABLE deliveries
    ADD COLUMN destination_city VARCHAR(100),
    ADD COLUMN destination_state VARCHAR(2),
    ADD COLUMN destination_address VARCHAR(255);

-- Dados da cotação: preenchidos pela integração de precificação.
ALTER TABLE deliveries
    ADD COLUMN distance_km NUMERIC(10, 2),
    ADD COLUMN quoted_price_cents BIGINT;

CREATE INDEX idx_deliveries_mode_status
    ON deliveries (mode, status);

CREATE INDEX idx_deliveries_origin_destination
    ON deliveries (origin_city, destination_city);
