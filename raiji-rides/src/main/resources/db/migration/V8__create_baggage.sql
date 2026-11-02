-- S2-01 / RF18: bagagem declarada na solicitação de vaga.
-- Tabela ÚNICA com coluna "type" VARCHAR (sem tabela separada por tipo).
-- CHECKs espelham as regras de negócio: quantity >= 1 e dimensões > 0.

CREATE TABLE IF NOT EXISTS baggage (
    id UUID PRIMARY KEY,
    ride_passenger_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    length_cm INT NOT NULL,
    width_cm INT NOT NULL,
    height_cm INT NOT NULL,
    description TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0,
    CONSTRAINT fk_baggage_ride_passengers
        FOREIGN KEY (ride_passenger_id) REFERENCES ride_passengers(id),
    CONSTRAINT chk_baggage_type
        CHECK (type IN ('MOCHILA', 'SACOLA', 'MALA', 'CAIXA')),
    CONSTRAINT chk_baggage_quantity
        CHECK (quantity >= 1),
    CONSTRAINT chk_baggage_dimensions
        CHECK (length_cm > 0 AND width_cm > 0 AND height_cm > 0)
);

CREATE INDEX IF NOT EXISTS idx_baggage_ride_passenger ON baggage(ride_passenger_id);
