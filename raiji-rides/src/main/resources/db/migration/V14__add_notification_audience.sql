-- V14: em qual modo do app a notificação aparece (a mesma conta pode ser
-- passageira e motorista). PASSENGER | DRIVER.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS audience VARCHAR(20);

-- Notificações já existentes: as destinadas ao motorista são as de
-- "nova solicitação" e "reserva cancelada" (ver NotificationService).
UPDATE notifications
   SET audience = CASE
       WHEN message LIKE 'Nova solicitação%' OR message LIKE 'A reserva de%' THEN 'DRIVER'
       ELSE 'PASSENGER'
   END
 WHERE audience IS NULL;

ALTER TABLE notifications
    ALTER COLUMN audience SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_audience_created
    ON notifications(recipient_sub, audience, created_at);
