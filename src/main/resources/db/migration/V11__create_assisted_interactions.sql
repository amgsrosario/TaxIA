-- Assistive circuit: fast AI Q&A sessions linked to a client
CREATE TABLE assisted_interactions (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    client_id       UUID         NOT NULL REFERENCES clients(id),
    created_by_user_id UUID      NOT NULL REFERENCES users(id),
    title           VARCHAR(254) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    -- If promoted to a formal KnowledgeCase, link is stored here
    promoted_case_id UUID        REFERENCES knowledge_cases(id),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_assisted_interactions_organization_id ON assisted_interactions(organization_id);
CREATE INDEX idx_assisted_interactions_client_id       ON assisted_interactions(client_id);

-- Individual messages within an assistive interaction
CREATE TABLE assisted_interaction_messages (
    id                      UUID        PRIMARY KEY,
    interaction_id          UUID        NOT NULL REFERENCES assisted_interactions(id),
    question                TEXT        NOT NULL,
    answer                  TEXT        NOT NULL,
    model_used              VARCHAR(80),
    input_tokens            INTEGER     NOT NULL DEFAULT 0,
    output_tokens           INTEGER     NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_assisted_interaction_messages_interaction_id
    ON assisted_interaction_messages(interaction_id);
