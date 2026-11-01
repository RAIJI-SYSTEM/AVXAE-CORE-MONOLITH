CREATE TABLE IF NOT EXISTS notifications (
                                             id UUID PRIMARY KEY,
                                             recipient_sub VARCHAR(64) NOT NULL,
    ride_id UUID NOT NULL,
    message VARCHAR(500) NOT NULL,

    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
                             version BIGINT DEFAULT 0
                             );

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created
    ON notifications(recipient_sub, created_at);

CREATE INDEX IF NOT EXISTS idx_notifications_ride
    ON notifications(ride_id);