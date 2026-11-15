ALTER TABLE deliveries
    ADD COLUMN tracking_code VARCHAR(20);


UPDATE deliveries
SET tracking_code = 'R' || UPPER(SUBSTRING(REPLACE(id::TEXT, '-', '') FROM 1 FOR 19))
WHERE tracking_code IS NULL;


ALTER TABLE deliveries
    ALTER COLUMN tracking_code SET NOT NULL;

ALTER TABLE deliveries
    ADD CONSTRAINT uq_deliveries_tracking_code UNIQUE (tracking_code);

CREATE INDEX idx_deliveries_tracking
    ON deliveries (tracking_code);


CREATE TABLE delivery_status_history (
                                         id UUID PRIMARY KEY,
                                         delivery_id UUID NOT NULL REFERENCES deliveries(id),
                                         from_status VARCHAR(20),
                                         to_status VARCHAR(20) NOT NULL,
                                         changed_by VARCHAR(64),
                                         reason VARCHAR(300),
                                         created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

                                         CONSTRAINT chk_delivery_history_to_status
                                             CHECK (to_status IN ('PENDING', 'CONFIRMED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')),

                                         CONSTRAINT chk_delivery_history_from_status
                                             CHECK (
                                                 from_status IS NULL OR
                                                 from_status IN ('PENDING', 'CONFIRMED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')
                                                 )
);

CREATE INDEX idx_delivery_history_delivery
    ON delivery_status_history (delivery_id, created_at);
