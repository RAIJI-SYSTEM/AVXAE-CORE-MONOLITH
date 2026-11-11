
CREATE TABLE ride_intents (
                              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                              user_sub VARCHAR(64) NOT NULL,

                              type VARCHAR(30) NOT NULL,
                              mode VARCHAR(20) NOT NULL,
                              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

                              origin_city VARCHAR(100),
                              origin_state CHAR(2),

                              destination_city VARCHAR(100),
                              destination_state CHAR(2),

                              origin_location GEOGRAPHY(POINT, 4326),
                              destination_location GEOGRAPHY(POINT, 4326),

                              desired_at TIMESTAMPTZ,
                              expires_at TIMESTAMPTZ NOT NULL,

                              created_by VARCHAR(64),
                              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                              updated_by VARCHAR(64),
                              updated_at TIMESTAMPTZ,
                              deleted_at TIMESTAMPTZ,
                              version BIGINT NOT NULL DEFAULT 0,

                              CONSTRAINT chk_ride_intents_type
                                  CHECK (type IN ('PASSENGER_REQUEST', 'DRIVER_OFFER')),

                              CONSTRAINT chk_ride_intents_mode
                                  CHECK (mode IN ('ROUTE', 'FREE')),

                              CONSTRAINT chk_ride_intents_status
                                  CHECK (status IN ('ACTIVE', 'MATCHED', 'CANCELLED', 'EXPIRED')),

                              CONSTRAINT chk_ride_intents_free_mode
                                  CHECK (
                                      mode <> 'FREE'
                                          OR type = 'DRIVER_OFFER'
                                      ),

                              CONSTRAINT chk_ride_intents_expiration
                                  CHECK (expires_at > created_at)
);

CREATE INDEX idx_ride_intents_status
    ON ride_intents(status);

CREATE INDEX idx_ride_intents_user
    ON ride_intents(user_sub);

CREATE INDEX idx_ride_intents_type_mode
    ON ride_intents(type, mode);

CREATE INDEX idx_ride_intents_expiration
    ON ride_intents(expires_at);

CREATE INDEX idx_ride_intents_origin_location
    ON ride_intents USING GIST(origin_location);

CREATE INDEX idx_ride_intents_destination_location
    ON ride_intents USING GIST(destination_location);
