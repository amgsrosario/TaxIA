CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    tax_identifier VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_organizations_tax_identifier UNIQUE (tax_identifier)
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_roles_name UNIQUE (name)
);

CREATE TABLE organization_users (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_organization_users_org_user_role UNIQUE (organization_id, user_id, role_id)
);

CREATE INDEX idx_organization_users_organization_id ON organization_users(organization_id);
CREATE INDEX idx_organization_users_user_id ON organization_users(user_id);
CREATE INDEX idx_organization_users_role_id ON organization_users(role_id);

INSERT INTO roles (id, name, description, created_at) VALUES
    ('10000000-0000-0000-0000-000000000001', 'ADMIN', 'Administra a organizacao e utilizadores', NOW()),
    ('10000000-0000-0000-0000-000000000002', 'AUTHOR', 'Cria e edita pareceres em rascunho', NOW()),
    ('10000000-0000-0000-0000-000000000003', 'REVIEWER', 'Revê pareceres antes da validacao', NOW()),
    ('10000000-0000-0000-0000-000000000004', 'VALIDATOR', 'Valida ou rejeita pareceres', NOW()),
    ('10000000-0000-0000-0000-000000000005', 'VIEWER', 'Consulta informacao autorizada', NOW());
