-- Aparelhos que recebem notificação push (Firebase Cloud Messaging).
-- Um token é de um aparelho; se outra pessoa entrar nele, o token passa a ser dela.
CREATE TABLE push_devices (
    token       VARCHAR(512) PRIMARY KEY,
    user_sub    VARCHAR(255) NOT NULL,
    platform    VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_push_devices_user ON push_devices (user_sub);
