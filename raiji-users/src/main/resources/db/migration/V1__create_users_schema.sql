-- V1: schema do módulo users (agora só vehicles; users vive no Keycloak)

CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    owner_sub VARCHAR(64) NOT NULL,
    plate VARCHAR(7) UNIQUE NOT NULL,
    type VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    color VARCHAR(50),
    year INT,
    passenger_seats INT DEFAULT 4,
    cargo_volume_liters INT,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_vehicles_plate ON vehicles(plate);
CREATE INDEX IF NOT EXISTS idx_vehicles_owner_sub ON vehicles(owner_sub);
