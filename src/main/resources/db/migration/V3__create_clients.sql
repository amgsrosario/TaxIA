CREATE TABLE clients (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(180) NOT NULL,
    tax_identifier VARCHAR(64),
    contact_email VARCHAR(254),
    phone VARCHAR(64),
    notes TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_clients_org_tax_identifier UNIQUE (organization_id, tax_identifier)
);

CREATE INDEX idx_clients_organization_id ON clients(organization_id);
CREATE INDEX idx_clients_organization_status ON clients(organization_id, status);
CREATE INDEX idx_clients_organization_name ON clients(organization_id, name);
