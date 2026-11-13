-- Empresas (PJ): cadastradas pelo CNPJ (dados conferidos na Receita) e geridas
-- por várias pessoas (membros). Qualquer membro ativo cria pedidos e aprova pagamentos;
-- o dono (OWNER) também remove membros.
CREATE TABLE IF NOT EXISTS companies (
    id                   UUID PRIMARY KEY,
    cnpj                 VARCHAR(14)  NOT NULL UNIQUE,
    legal_name           VARCHAR(200) NOT NULL,
    trade_name           VARCHAR(200),
    -- MEI (microempreendedor individual) ou EMPRESA
    kind                 VARCHAR(20)  NOT NULL,
    registration_status  VARCHAR(40)  NOT NULL,
    main_activity        VARCHAR(255),
    city                 VARCHAR(100),
    state                VARCHAR(2),
    verified_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by           VARCHAR(64),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by           VARCHAR(64),
    updated_at           TIMESTAMP WITH TIME ZONE,
    deleted_at           TIMESTAMP WITH TIME ZONE,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_companies_kind CHECK (kind IN ('MEI', 'EMPRESA')),
    CONSTRAINT chk_companies_cnpj CHECK (cnpj ~ '^[0-9]{14}$')
);

CREATE TABLE IF NOT EXISTS company_members (
    id            UUID PRIMARY KEY,
    company_id    UUID NOT NULL REFERENCES companies(id),
    -- preenchido quando a pessoa aceita o convite (antes, só o e-mail)
    user_sub      VARCHAR(64),
    email         VARCHAR(254) NOT NULL,
    display_name  VARCHAR(150),
    role          VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    invited_by    VARCHAR(64),
    invited_at    TIMESTAMP WITH TIME ZONE,
    joined_at     TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(64),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by    VARCHAR(64),
    updated_at    TIMESTAMP WITH TIME ZONE,
    deleted_at    TIMESTAMP WITH TIME ZONE,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_company_members_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT chk_company_members_status CHECK (status IN ('INVITED', 'ACTIVE', 'REMOVED', 'DECLINED'))
);

-- Um convite/participação em aberto por e-mail em cada empresa
CREATE UNIQUE INDEX IF NOT EXISTS uq_company_members_open_email
    ON company_members (company_id, lower(email))
    WHERE status IN ('INVITED', 'ACTIVE');
CREATE INDEX IF NOT EXISTS idx_company_members_sub ON company_members (user_sub) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_company_members_email ON company_members (lower(email)) WHERE status = 'INVITED';
