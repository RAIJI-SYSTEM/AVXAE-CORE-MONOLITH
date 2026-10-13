-- V5: migração User/UserProfile -> Keycloak
--
-- Idempotente: funciona tanto em bancos ANTIGOS (que tinham users/user_profiles
-- e colunas *_id UUID) quanto em bancos NOVOS (onde o V1 revisado já criou as
-- colunas *_sub VARCHAR direto). Se a coluna já tiver o nome novo, o bloco
-- simplesmente ignora o RENAME.

-- 1) drop legado (idempotente)
DROP TABLE IF EXISTS user_profiles;
DROP TABLE IF EXISTS users;

-- 2) vehicles.owner_id -> owner_sub
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'vehicles' AND column_name = 'owner_id') THEN
        ALTER TABLE vehicles RENAME COLUMN owner_id TO owner_sub;
        ALTER TABLE vehicles ALTER COLUMN owner_sub TYPE VARCHAR(64) USING owner_sub::text;
    END IF;
END $$;

-- 3) rides.driver_id -> driver_sub
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'rides' AND column_name = 'driver_id') THEN
        ALTER TABLE rides RENAME COLUMN driver_id TO driver_sub;
        ALTER TABLE rides ALTER COLUMN driver_sub TYPE VARCHAR(64) USING driver_sub::text;
    END IF;
END $$;

-- 4) ride_passengers.passenger_id -> passenger_sub
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ride_passengers' AND column_name = 'passenger_id') THEN
        ALTER TABLE ride_passengers RENAME COLUMN passenger_id TO passenger_sub;
        ALTER TABLE ride_passengers ALTER COLUMN passenger_sub TYPE VARCHAR(64) USING passenger_sub::text;
    END IF;
END $$;

-- 5) deliveries.sender_id -> sender_sub
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'deliveries' AND column_name = 'sender_id') THEN
        ALTER TABLE deliveries RENAME COLUMN sender_id TO sender_sub;
        ALTER TABLE deliveries ALTER COLUMN sender_sub TYPE VARCHAR(64) USING sender_sub::text;
    END IF;
END $$;

-- 6) renomeia índices (opcional)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_vehicles_owner') THEN
        ALTER INDEX idx_vehicles_owner RENAME TO idx_vehicles_owner_sub;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_rides_driver_status') THEN
        ALTER INDEX idx_rides_driver_status RENAME TO idx_rides_driver_sub_status;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_ride_passengers_passenger') THEN
        ALTER INDEX idx_ride_passengers_passenger RENAME TO idx_ride_pass_passenger_sub;
    END IF;
END $$;
