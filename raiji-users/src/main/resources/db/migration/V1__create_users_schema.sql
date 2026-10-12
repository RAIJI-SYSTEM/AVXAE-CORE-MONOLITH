-- V1: schema do módulo users (User / Vehicle)
-- Mantém também a tabela legada user_profiles para compatibilidade.

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    keycloak_sub VARCHAR(64) UNIQUE NOT NULL,
    user_type VARCHAR(10) NOT NULL,
    name VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    phone VARCHAR(20),
    document VARCHAR(14) UNIQUE NOT NULL,
    photo_url VARCHAR(500),
    active BOOLEAN DEFAULT TRUE NOT NULL,
    cnh_number VARCHAR(20) UNIQUE,
    cnh_category VARCHAR(5),
    cnh_validity DATE,
    is_driver_verified BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0
);

ALTER TABLE users ADD CONSTRAINT chk_user_document_format
    CHECK ((user_type = 'PF' AND length(document) = 11)
        OR (user_type = 'PJ' AND length(document) = 14));

CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
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

CREATE INDEX IF NOT EXISTS idx_users_keycloak ON users(keycloak_sub);
CREATE INDEX IF NOT EXISTS idx_users_document ON users(document);
CREATE INDEX IF NOT EXISTS idx_vehicles_plate ON vehicles(plate);
CREATE INDEX IF NOT EXISTS idx_vehicles_owner ON vehicles(owner_id);
