-- M4-SCOPE-V2: governed applicability exclusions (ADR-004).
-- A Q&A may declare, from a closed vocabulary validated in the application, situations it does NOT
-- cover (e.g. INQUILINO for a landlord-only answer). The grounding scope gate rejects the Q&A as a
-- source when the question carries an excluded marker. Not part of the embedding: no reindex.

CREATE TABLE knowledge_qa_applicability_exclusions (
    id                     UUID         NOT NULL PRIMARY KEY,
    knowledge_qa_id        UUID         NOT NULL REFERENCES knowledge_question_answers(id) ON DELETE CASCADE,
    -- Vocabulary code; validated in the application (no CHECK, so a new marker needs no migration).
    marker                 VARCHAR(60)  NOT NULL,
    note                   VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(255),
    -- Removal of an exclusion from a published or VALIDATED Q&A widens its scope: it stays effective
    -- until a human approves the removal. Non-null = removal requested, exclusion still applied.
    removal_requested_at   TIMESTAMPTZ,
    removal_requested_by   VARCHAR(255),
    CONSTRAINT ux_kqa_applicability_marker UNIQUE (knowledge_qa_id, marker)
);

-- UNIQUE(knowledge_qa_id, marker) also serves lookups by knowledge_qa_id (leading column).

-- "Scope reviewed" marker: distinguishes "reviewed, no exclusions" from "never reviewed".
-- Optional; legacy rows stay NULL and remain publishable.
ALTER TABLE knowledge_question_answers
    ADD COLUMN applicability_reviewed_at TIMESTAMPTZ,
    ADD COLUMN applicability_reviewed_by VARCHAR(255);
