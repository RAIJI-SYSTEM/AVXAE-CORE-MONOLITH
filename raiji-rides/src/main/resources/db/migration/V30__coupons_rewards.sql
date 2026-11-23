-- Recompensas: cupons de desconto, progresso da missão de anúncios e anúncios já creditados.
-- O desconto é pago pela plataforma: o repasse do motorista usa o preço cheio.

CREATE TABLE coupons (
    id              UUID PRIMARY KEY,
    user_sub        VARCHAR(255) NOT NULL,
    code            VARCHAR(40)  NOT NULL UNIQUE,
    title           VARCHAR(120) NOT NULL,
    description     VARCHAR(300) NOT NULL,
    discount_cents  BIGINT       NOT NULL CHECK (discount_cents > 0),
    -- WELCOME (boas-vindas), ADS (missão de anúncios), ADMIN
    source          VARCHAR(20)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    -- Preenchido quando o pagamento com o cupom é confirmado; volta a null se for estornado
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_coupons_user ON coupons (user_sub);
-- Um cupom de boas-vindas por pessoa
CREATE UNIQUE INDEX uq_coupons_welcome ON coupons (user_sub) WHERE source = 'WELCOME';

CREATE TABLE reward_progress (
    user_sub    VARCHAR(255) PRIMARY KEY,
    points      INT          NOT NULL DEFAULT 0,
    completed   BOOLEAN      NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- O mesmo anúncio (transaction_id do AdMob) nunca vale duas vezes
CREATE TABLE reward_ad_credits (
    transaction_id  VARCHAR(200) PRIMARY KEY,
    user_sub        VARCHAR(255) NOT NULL,
    ad_kind         VARCHAR(10)  NOT NULL,
    points          INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Cupom aplicado no pagamento (o valor cobrado já é o preço menos o desconto)
ALTER TABLE ride_passengers ADD COLUMN coupon_id UUID, ADD COLUMN discount_cents BIGINT;
ALTER TABLE deliveries      ADD COLUMN coupon_id UUID, ADD COLUMN discount_cents BIGINT;
