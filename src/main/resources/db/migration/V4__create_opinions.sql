CREATE TABLE opinions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    client_id UUID NOT NULL REFERENCES clients(id),
    title VARCHAR(240) NOT NULL,
    question TEXT NOT NULL,
    content TEXT,
    status VARCHAR(40) NOT NULL,
    current_version_number INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_opinions_organization_id ON opinions(organization_id);
CREATE INDEX idx_opinions_client_id ON opinions(client_id);
CREATE INDEX idx_opinions_organization_status ON opinions(organization_id, status);
CREATE INDEX idx_opinions_organization_created_at ON opinions(organization_id, created_at);

CREATE TABLE opinion_versions (
    id UUID PRIMARY KEY,
    opinion_id UUID NOT NULL REFERENCES opinions(id),
    version_number INTEGER NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    title VARCHAR(240) NOT NULL,
    question TEXT NOT NULL,
    content TEXT,
    status_snapshot VARCHAR(40) NOT NULL,
    is_validated_version BOOLEAN NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_opinion_versions_opinion_number UNIQUE (opinion_id, version_number)
);

CREATE INDEX idx_opinion_versions_opinion_id ON opinion_versions(opinion_id);
CREATE INDEX idx_opinion_versions_created_by_user_id ON opinion_versions(created_by_user_id);
