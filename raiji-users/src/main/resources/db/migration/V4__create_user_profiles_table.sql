-- V4: tabela legada user_profiles
-- Mantida para compatibilidade com UserProfile (usado por UserProvisioningService
-- e UserProfileController). Será removida quando esses forem migrados para User.

CREATE TABLE IF NOT EXISTS user_profiles (
    id           UUID         PRIMARY KEY,
    keycloak_sub VARCHAR(64)  UNIQUE NOT NULL,
    email        VARCHAR(160),
    full_name    VARCHAR(160),
    profile_type VARCHAR(20),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_profiles_keycloak_sub ON user_profiles(keycloak_sub);
