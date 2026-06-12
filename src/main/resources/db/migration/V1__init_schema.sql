CREATE TABLE schema_version_marker (
    id UUID PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
