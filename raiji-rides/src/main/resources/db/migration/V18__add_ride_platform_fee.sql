-- Comissão da plataforma gravada na carona no momento da publicação
-- (mudar a configuração depois não altera caronas já publicadas).
-- Caronas anteriores a esta migração foram publicadas sem comissão: 0.
ALTER TABLE rides
    ADD COLUMN platform_fee_percent NUMERIC(5, 2) NOT NULL DEFAULT 0
        CHECK (platform_fee_percent >= 0 AND platform_fee_percent <= 100);
