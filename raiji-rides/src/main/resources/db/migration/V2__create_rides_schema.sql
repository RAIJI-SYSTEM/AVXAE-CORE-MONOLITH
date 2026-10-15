CREATE TABLE IF NOT EXISTS rides (
    id UUID PRIMARY KEY,
    ride_type VARCHAR(20) NOT NULL,
    driver_id UUID NOT NULL,
    driver_name VARCHAR(150),
    vehicle_id UUID NOT NULL,
    origin_city VARCHAR(100) NOT NULL,
    origin_state VARCHAR(2) NOT NULL,
    destination_city VARCHAR(100) NOT NULL,
    destination_state VARCHAR(2) NOT NULL,
    departure_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    price_per_passenger NUMERIC(10, 2),
    total_seats INT NOT NULL DEFAULT 4,
    available_seats INT NOT NULL DEFAULT 4,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    cargo_max_weight_kg NUMERIC(10, 2),
    cargo_max_length_cm NUMERIC(8, 2),
    cargo_max_width_cm NUMERIC(8, 2),
    cargo_max_height_cm NUMERIC(8, 2),
    cargo_observation TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0,
    CONSTRAINT chk_seats_consistency CHECK (available_seats >= 0 AND available_seats <= total_seats)
);

CREATE TABLE IF NOT EXISTS ride_passengers (
    id UUID PRIMARY KEY,
    ride_id UUID NOT NULL,
    passenger_id UUID NOT NULL,
    passenger_name VARCHAR(150),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    confirmed_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_rides_route_date ON rides(origin_city, destination_city, departure_date);
CREATE INDEX IF NOT EXISTS idx_rides_driver_status ON rides(driver_id, status);
CREATE INDEX IF NOT EXISTS idx_ride_passengers_ride ON ride_passengers(ride_id);
CREATE INDEX IF NOT EXISTS idx_ride_passengers_passenger ON ride_passengers(passenger_id);
