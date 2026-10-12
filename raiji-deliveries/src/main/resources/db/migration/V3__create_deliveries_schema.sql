CREATE TABLE IF NOT EXISTS deliveries (
    id UUID PRIMARY KEY,
    tracking_code VARCHAR(20) UNIQUE NOT NULL,
    access_key VARCHAR(6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ride_id UUID,
    sender_id UUID NOT NULL,
    sender_name VARCHAR(150),
    recipient_name VARCHAR(150) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    recipient_document VARCHAR(14),
    zip_code VARCHAR(8) NOT NULL,
    street VARCHAR(150) NOT NULL,
    number VARCHAR(20) NOT NULL,
    neighborhood VARCHAR(100),
    city VARCHAR(100) NOT NULL,
    state VARCHAR(2) NOT NULL,
    complement VARCHAR(100),
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0,
    CONSTRAINT chk_access_key_length CHECK (length(access_key) = 6)
);

CREATE TABLE IF NOT EXISTS items (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    description VARCHAR(200) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    weight_kg NUMERIC(10, 2) NOT NULL,
    fragile BOOLEAN DEFAULT FALSE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0,
    CONSTRAINT chk_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_deliveries_tracking ON deliveries(tracking_code);
CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(status);
CREATE INDEX IF NOT EXISTS idx_items_delivery ON items(delivery_id);
