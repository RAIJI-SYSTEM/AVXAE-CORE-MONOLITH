-- S2-05 / RN04 — trilha de auditoria (usada p/ registrar estornos).
--
-- Tabela enxuta e independente (não herda as colunas de soft-delete/versão):
-- guarda quem fez o quê, em qual entidade e os detalhes (ex.: id do refund,
-- valor, payment_intent, erro). Ações de estorno: ESTORNO_SUCESSO /
-- ESTORNO_FALHOU.

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    action VARCHAR(50) NOT NULL,
    actor_sub VARCHAR(64),
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
