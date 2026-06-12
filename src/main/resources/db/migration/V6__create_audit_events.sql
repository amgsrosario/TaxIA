CREATE TABLE audit_events (
    id            UUID        PRIMARY KEY,
    organization_id UUID      NOT NULL REFERENCES organizations(id),
    user_id       UUID                 REFERENCES users(id),
    action        VARCHAR(80) NOT NULL,
    entity_type   VARCHAR(80) NOT NULL,
    entity_id     UUID        NOT NULL,
    metadata      TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_events_organization_id  ON audit_events(organization_id);
CREATE INDEX idx_audit_events_entity           ON audit_events(entity_type, entity_id);
CREATE INDEX idx_audit_events_user_id          ON audit_events(user_id);
CREATE INDEX idx_audit_events_occurred_at      ON audit_events(occurred_at);
