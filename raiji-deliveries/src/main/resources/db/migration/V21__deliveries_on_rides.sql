-- Encomenda = "espaço de carga" numa carona publicada (do mesmo jeito que o
-- passageiro pede vaga), e propostas de frete das empresas.
--
-- As tabelas antigas (V3) nunca tiveram serviço nem endpoint: são recriadas.
-- Proteção: se por acaso houver dados, a migração para (não apaga nada).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM deliveries) OR EXISTS (SELECT 1 FROM items) THEN
        RAISE EXCEPTION 'deliveries/items têm dados: migre-os antes de aplicar a V21';
    END IF;
END $$;

DROP TABLE IF EXISTS items;
DROP TABLE IF EXISTS deliveries;

CREATE TABLE deliveries (
    id                    UUID PRIMARY KEY,
    ride_id               UUID NOT NULL REFERENCES rides(id),
    sender_sub            VARCHAR(64) NOT NULL,
    sender_name           VARCHAR(150),
    -- enviada em nome de uma empresa (qualquer membro acompanha)
    company_id            UUID REFERENCES companies(id),
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    package_size          VARCHAR(20) NOT NULL,
    description           VARCHAR(200) NOT NULL,
    weight_kg             NUMERIC(8, 2) NOT NULL,
    length_cm             INTEGER NOT NULL,
    width_cm              INTEGER NOT NULL,
    height_cm             INTEGER NOT NULL,
    fragile               BOOLEAN NOT NULL DEFAULT FALSE,
    declared_value        NUMERIC(12, 2),

    recipient_name        VARCHAR(150) NOT NULL,
    recipient_phone       VARCHAR(20) NOT NULL,

    pickup_location       GEOGRAPHY(POINT, 4326),
    pickup_address        VARCHAR(255),
    dropoff_location      GEOGRAPHY(POINT, 4326),
    dropoff_address       VARCHAR(255),
    full_route            BOOLEAN NOT NULL DEFAULT TRUE,

    price                 NUMERIC(10, 2),
    platform_fee_percent  NUMERIC(5, 2) NOT NULL DEFAULT 0,

    -- coleta: o remetente mostra o código ao motorista; entrega: o destinatário diz a palavra-chave
    pickup_code           VARCHAR(4) NOT NULL,
    delivery_keyword      VARCHAR(12) NOT NULL,
    failed_code_attempts  INTEGER NOT NULL DEFAULT 0,

    freight_request_id    UUID,
    payment_intent_id     VARCHAR(100),
    requested_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    confirmed_at          TIMESTAMP WITH TIME ZONE,
    picked_up_at          TIMESTAMP WITH TIME ZONE,
    delivered_at          TIMESTAMP WITH TIME ZONE,
    cancelled_at          TIMESTAMP WITH TIME ZONE,
    cancellation_reason   TEXT,

    created_by            VARCHAR(64),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by            VARCHAR(64),
    updated_at            TIMESTAMP WITH TIME ZONE,
    deleted_at            TIMESTAMP WITH TIME ZONE,
    version               BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_deliveries_status CHECK (status IN ('PENDING', 'CONFIRMED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT chk_deliveries_size CHECK (package_size IN ('ENVELOPE', 'SMALL', 'MEDIUM', 'LARGE')),
    CONSTRAINT chk_deliveries_weight CHECK (weight_kg > 0),
    CONSTRAINT chk_deliveries_dimensions CHECK (length_cm > 0 AND width_cm > 0 AND height_cm > 0)
);

CREATE INDEX idx_deliveries_ride ON deliveries (ride_id);
CREATE INDEX idx_deliveries_sender ON deliveries (sender_sub);
CREATE INDEX idx_deliveries_company ON deliveries (company_id);

-- Propostas de frete de empresas:
--  * direta: para um motorista (target_driver_sub), com valor; ele aceita ou recusa;
--  * pública: "preciso levar X para Y"; motoristas oferecem um valor e a empresa escolhe.
CREATE TABLE freight_requests (
    id                    UUID PRIMARY KEY,
    company_id            UUID NOT NULL REFERENCES companies(id),
    created_by_sub        VARCHAR(64) NOT NULL,
    target_driver_sub     VARCHAR(64),
    status                VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    origin_city           VARCHAR(100) NOT NULL,
    origin_state          VARCHAR(2) NOT NULL,
    origin_location       GEOGRAPHY(POINT, 4326) NOT NULL,
    origin_address        VARCHAR(255),
    destination_city      VARCHAR(100) NOT NULL,
    destination_state     VARCHAR(2) NOT NULL,
    destination_location  GEOGRAPHY(POINT, 4326) NOT NULL,
    destination_address   VARCHAR(255),
    desired_date          TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    package_size          VARCHAR(20) NOT NULL,
    description           VARCHAR(200) NOT NULL,
    weight_kg             NUMERIC(8, 2) NOT NULL,
    length_cm             INTEGER NOT NULL,
    width_cm              INTEGER NOT NULL,
    height_cm             INTEGER NOT NULL,
    fragile               BOOLEAN NOT NULL DEFAULT FALSE,
    declared_value        NUMERIC(12, 2),
    recipient_name        VARCHAR(150) NOT NULL,
    recipient_phone       VARCHAR(20) NOT NULL,

    -- direta: valor proposto (obrigatório); pública: valor de referência (opcional)
    offered_price         NUMERIC(10, 2),
    notes                 VARCHAR(500),

    accepted_offer_id     UUID,
    ride_id               UUID REFERENCES rides(id),
    delivery_id           UUID REFERENCES deliveries(id),
    decline_reason        VARCHAR(500),
    closed_at             TIMESTAMP WITH TIME ZONE,

    created_by            VARCHAR(64),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by            VARCHAR(64),
    updated_at            TIMESTAMP WITH TIME ZONE,
    deleted_at            TIMESTAMP WITH TIME ZONE,
    version               BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_freight_status CHECK (status IN ('OPEN', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT chk_freight_size CHECK (package_size IN ('ENVELOPE', 'SMALL', 'MEDIUM', 'LARGE')),
    CONSTRAINT chk_freight_direct_price CHECK (target_driver_sub IS NULL OR offered_price IS NOT NULL)
);

CREATE INDEX idx_freight_requests_company ON freight_requests (company_id, status);
CREATE INDEX idx_freight_requests_target ON freight_requests (target_driver_sub, status);
CREATE INDEX idx_freight_requests_origin ON freight_requests USING GIST (origin_location);

CREATE TABLE freight_offers (
    id             UUID PRIMARY KEY,
    request_id     UUID NOT NULL REFERENCES freight_requests(id),
    driver_sub     VARCHAR(64) NOT NULL,
    driver_name    VARCHAR(150),
    vehicle_id     UUID NOT NULL,
    price          NUMERIC(10, 2) NOT NULL,
    message        VARCHAR(500),
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_by     VARCHAR(64),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by     VARCHAR(64),
    updated_at     TIMESTAMP WITH TIME ZONE,
    deleted_at     TIMESTAMP WITH TIME ZONE,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_freight_offers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT chk_freight_offers_price CHECK (price >= 0)
);

-- Uma oferta pendente por motorista em cada pedido
CREATE UNIQUE INDEX uq_freight_offers_pending
    ON freight_offers (request_id, driver_sub) WHERE status = 'PENDING';
CREATE INDEX idx_freight_offers_driver ON freight_offers (driver_sub, status);
