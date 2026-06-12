-- Commercial plans catalogue
CREATE TABLE commercial_plans (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    plan_type   VARCHAR(40)  NOT NULL,
    max_cases   INTEGER,
    max_interactions INTEGER,
    max_portal_users INTEGER,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- Assigns a commercial plan to an organization for a period
CREATE TABLE organization_plans (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    plan_id         UUID        NOT NULL REFERENCES commercial_plans(id),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_organization_plans_organization_id ON organization_plans(organization_id);
CREATE INDEX idx_organization_plans_active ON organization_plans(organization_id, starts_at, ends_at);

-- Append-only consumption log
CREATE TABLE consumption_events (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    event_type      VARCHAR(80) NOT NULL,
    entity_id       UUID        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_consumption_events_organization_id ON consumption_events(organization_id);
CREATE INDEX idx_consumption_events_occurred_at     ON consumption_events(organization_id, occurred_at);
