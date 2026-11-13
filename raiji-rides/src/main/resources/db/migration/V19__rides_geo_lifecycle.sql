-- Viagem única para "agora" e agendada, com coordenadas e rota (busca por
-- proximidade, de A para B e ao longo do caminho) e ciclo de vida completo.

ALTER TABLE rides
    ADD COLUMN IF NOT EXISTS origin_location       GEOGRAPHY(POINT, 4326),
    ADD COLUMN IF NOT EXISTS destination_location  GEOGRAPHY(POINT, 4326),
    ADD COLUMN IF NOT EXISTS origin_address        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS destination_address   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS meeting_point         VARCHAR(255),
    -- Preço por encomenda (viagem CARGO/MIXED); null = encomendas grátis
    ADD COLUMN IF NOT EXISTS price_per_package     NUMERIC(10, 2),
    -- Caminho da viagem (Mapbox Directions no app; sem ele, linha reta origem -> destino)
    ADD COLUMN IF NOT EXISTS route                 GEOGRAPHY(LINESTRING, 4326),
    ADD COLUMN IF NOT EXISTS route_distance_meters INTEGER,
    -- PUBLIC aparece na busca; PRIVATE = viagem exclusiva (frete combinado com empresa)
    ADD COLUMN IF NOT EXISTS visibility            VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    -- O motorista encerrou as reservas (não aceita novos pedidos)
    ADD COLUMN IF NOT EXISTS bookings_closed       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS started_at            TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS completed_at          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS cancelled_at          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS cancellation_reason   TEXT;

ALTER TABLE rides DROP CONSTRAINT IF EXISTS chk_rides_visibility;
ALTER TABLE rides ADD CONSTRAINT chk_rides_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

CREATE INDEX IF NOT EXISTS idx_rides_origin_location ON rides USING GIST (origin_location);
CREATE INDEX IF NOT EXISTS idx_rides_destination_location ON rides USING GIST (destination_location);
CREATE INDEX IF NOT EXISTS idx_rides_route ON rides USING GIST (route);
CREATE INDEX IF NOT EXISTS idx_rides_status_departure ON rides (status, departure_date);

-- Pedido de vaga num TRECHO da viagem (embarque/desembarque no caminho) e o preço do trecho
ALTER TABLE ride_passengers
    ADD COLUMN IF NOT EXISTS pickup_location   GEOGRAPHY(POINT, 4326),
    ADD COLUMN IF NOT EXISTS dropoff_location  GEOGRAPHY(POINT, 4326),
    ADD COLUMN IF NOT EXISTS pickup_address    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS dropoff_address   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS full_route        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS price             NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS completed_at      TIMESTAMP WITH TIME ZONE;

-- Bagagem opcional e editável: uma alteração que AUMENTA a carga depois do
-- aceite fica pendente até o motorista aprovar (pending_approval = true).
ALTER TABLE baggage
    ADD COLUMN IF NOT EXISTS pending_approval BOOLEAN NOT NULL DEFAULT FALSE;

-- Notificações de qualquer assunto (viagem, encomenda, empresa, frete) e leitura
ALTER TABLE notifications ALTER COLUMN ride_id DROP NOT NULL;
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS kind          VARCHAR(40),
    ADD COLUMN IF NOT EXISTS reference_id  UUID,
    ADD COLUMN IF NOT EXISTS read_at       TIMESTAMP WITH TIME ZONE;
