-- Mural da região: pedidos AGENDADOS (mais de 1h) de passageiros e remetentes. Motoristas perto
-- (ou com carona publicada no caminho) oferecem um valor; o dono do pedido escolhe.
CREATE TABLE board_requests (
    id                    UUID PRIMARY KEY,
    kind                  VARCHAR(10) NOT NULL,
    requester_sub         VARCHAR(64) NOT NULL,
    requester_name        VARCHAR(150),
    -- encomenda publicada em nome de uma empresa (qualquer membro acompanha e escolhe)
    company_id            UUID REFERENCES companies(id),
    status                VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    origin_city           VARCHAR(100) NOT NULL,
    origin_state          VARCHAR(2) NOT NULL,
    origin_location       GEOGRAPHY(POINT, 4326) NOT NULL,
    origin_address        VARCHAR(255),
    destination_city      VARCHAR(100) NOT NULL,
    destination_state     VARCHAR(2) NOT NULL,
    destination_location  GEOGRAPHY(POINT, 4326) NOT NULL,
    destination_address   VARCHAR(255),
    -- hora de Brasília, como as caronas
    desired_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    notes                 VARCHAR(500),

    -- só encomenda (kind = PACKAGE)
    package_size          VARCHAR(20),
    description           VARCHAR(200),
    weight_kg             NUMERIC(8, 2),
    length_cm             INTEGER,
    width_cm              INTEGER,
    height_cm             INTEGER,
    fragile               BOOLEAN,
    declared_value        NUMERIC(12, 2),
    recipient_name        VARCHAR(150),
    recipient_phone       VARCHAR(20),

    accepted_offer_id     UUID,
    ride_id               UUID REFERENCES rides(id),
    booking_id            UUID REFERENCES ride_passengers(id),
    delivery_id           UUID REFERENCES deliveries(id),
    closed_at             TIMESTAMP WITH TIME ZONE,

    created_by            VARCHAR(64),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by            VARCHAR(64),
    updated_at            TIMESTAMP WITH TIME ZONE,
    deleted_at            TIMESTAMP WITH TIME ZONE,
    version               BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_board_kind CHECK (kind IN ('SEAT', 'PACKAGE')),
    CONSTRAINT chk_board_status CHECK (status IN ('OPEN', 'ACCEPTED', 'CANCELLED')),
    CONSTRAINT chk_board_package CHECK (kind = 'SEAT' OR (package_size IN ('ENVELOPE', 'SMALL', 'MEDIUM', 'LARGE')
        AND description IS NOT NULL AND weight_kg > 0 AND length_cm > 0 AND width_cm > 0 AND height_cm > 0
        AND recipient_name IS NOT NULL AND recipient_phone IS NOT NULL))
);
CREATE INDEX idx_board_requests_open ON board_requests (status, desired_at);
CREATE INDEX idx_board_requests_owner ON board_requests (requester_sub);
CREATE INDEX idx_board_requests_company ON board_requests (company_id);
CREATE INDEX idx_board_requests_origin ON board_requests USING GIST (origin_location);

CREATE TABLE board_offers (
    id           UUID PRIMARY KEY,
    request_id   UUID NOT NULL REFERENCES board_requests(id),
    driver_sub   VARCHAR(64) NOT NULL,
    driver_name  VARCHAR(150),
    price        NUMERIC(10, 2) NOT NULL,
    message      VARCHAR(500),
    -- leva numa carona que já publicou (ride_id) OU numa viagem nova, com este veículo (vehicle_id)
    ride_id      UUID REFERENCES rides(id),
    vehicle_id   UUID,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_by   VARCHAR(64),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(64),
    updated_at   TIMESTAMP WITH TIME ZONE,
    deleted_at   TIMESTAMP WITH TIME ZONE,
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_board_offer_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT chk_board_offer_price CHECK (price > 0),
    CONSTRAINT chk_board_offer_ride CHECK ((ride_id IS NULL) <> (vehicle_id IS NULL))
);
CREATE UNIQUE INDEX uq_board_offers_pending ON board_offers (request_id, driver_sub) WHERE status = 'PENDING';
CREATE INDEX idx_board_offers_driver ON board_offers (driver_sub, status);
