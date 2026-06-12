CREATE TABLE client_portal_users (
    id              UUID        PRIMARY KEY,
    client_id       UUID        NOT NULL REFERENCES clients(id),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uk_client_portal_users_client_email UNIQUE (client_id, email)
);

CREATE INDEX idx_client_portal_users_client_id       ON client_portal_users(client_id);
CREATE INDEX idx_client_portal_users_organization_id ON client_portal_users(organization_id);
