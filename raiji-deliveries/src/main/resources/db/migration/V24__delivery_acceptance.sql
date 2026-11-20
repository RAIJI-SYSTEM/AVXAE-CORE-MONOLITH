
-- Registra o motorista responsável pela encomenda aceita.
ALTER TABLE deliveries
    ADD COLUMN driver_sub VARCHAR(64);

-- Veículo utilizado no transporte.
-- Obrigatório para entregas EXCLUSIVE e opcional para ON_ROUTE.
ALTER TABLE deliveries
    ADD COLUMN vehicle_id UUID;

-- Momento em que o motorista aceitou a encomenda.
ALTER TABLE deliveries
    ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE;

-- Índice para localizar as entregas de um motorista.
CREATE INDEX idx_deliveries_driver_sub
    ON deliveries (driver_sub);

-- Índice para consultar entregas associadas a um veículo.
CREATE INDEX idx_deliveries_vehicle_id
    ON deliveries (vehicle_id);
