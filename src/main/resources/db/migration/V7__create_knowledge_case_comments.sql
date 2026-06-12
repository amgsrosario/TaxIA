CREATE TABLE knowledge_case_comments (
    id                UUID        PRIMARY KEY,
    knowledge_case_id UUID        NOT NULL REFERENCES knowledge_cases(id),
    created_by_user_id UUID       NOT NULL REFERENCES users(id),
    workflow_action   VARCHAR(80) NOT NULL,
    content           TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_knowledge_case_comments_case_id ON knowledge_case_comments(knowledge_case_id);
CREATE INDEX idx_knowledge_case_comments_user_id ON knowledge_case_comments(created_by_user_id);
