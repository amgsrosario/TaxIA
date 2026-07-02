-- Knowledge Q&A curation module
-- Stores validated question/answer pairs curated by the TaxIA team.
-- Separate from knowledge_cases (which requires a Client) to support
-- standalone import and RAG publication workflows.

-- -------------------------------------------------------------------------
-- Main Q&A table
-- -------------------------------------------------------------------------

CREATE TABLE knowledge_question_answers (
    id                       UUID         NOT NULL PRIMARY KEY,
    organization_id          UUID         NOT NULL REFERENCES organizations(id),

    external_key             VARCHAR(255),
    source_system            VARCHAR(120),

    -- Immutable originals (never overwritten after first import)
    original_question        TEXT         NOT NULL,
    original_answer          TEXT         NOT NULL,

    -- Curated variants (editable)
    normalized_question      TEXT,
    short_answer             TEXT,
    technical_answer         TEXT,

    -- Classification
    topic                    VARCHAR(60),
    subtopic                 VARCHAR(120),
    jurisdiction             VARCHAR(60)  DEFAULT 'PT',
    risk_level               VARCHAR(20)  DEFAULT 'MEDIUM'
                                          CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    requires_human_validation BOOLEAN     NOT NULL DEFAULT FALSE,

    -- Curation lifecycle
    curation_status          VARCHAR(30)  NOT NULL DEFAULT 'IMPORTED'
                                          CHECK (curation_status IN (
                                              'IMPORTED','PENDING_REVIEW','VALIDATED',
                                              'NEEDS_UPDATE','OUTDATED','REJECTED','ARCHIVED')),
    canonical                BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Validity window
    valid_from               DATE,
    valid_to                 DATE,

    -- Review metadata
    reviewed_at              TIMESTAMPTZ,
    reviewed_by              VARCHAR(255),

    -- Notes (curator)
    notes                    TEXT,

    -- Publication metadata
    published_at             TIMESTAMPTZ,
    published_by             VARCHAR(255),

    -- Versioning
    previous_version_id      UUID         REFERENCES knowledge_question_answers(id),

    -- Audit timestamps
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,

    -- Optimistic lock
    version                  INTEGER      NOT NULL DEFAULT 0
);

-- Idempotent import: same source entry never duplicated
CREATE UNIQUE INDEX ux_kqa_org_source_external
    ON knowledge_question_answers (organization_id, source_system, external_key)
    WHERE external_key IS NOT NULL AND source_system IS NOT NULL;

-- Query indexes
CREATE INDEX ix_kqa_organization  ON knowledge_question_answers (organization_id);
CREATE INDEX ix_kqa_status        ON knowledge_question_answers (curation_status);
CREATE INDEX ix_kqa_topic         ON knowledge_question_answers (topic);
CREATE INDEX ix_kqa_risk          ON knowledge_question_answers (risk_level);
CREATE INDEX ix_kqa_valid_to      ON knowledge_question_answers (valid_to) WHERE valid_to IS NOT NULL;
CREATE INDEX ix_kqa_published_at  ON knowledge_question_answers (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX ix_kqa_canonical     ON knowledge_question_answers (organization_id, topic, canonical)
    WHERE canonical = TRUE;

-- -------------------------------------------------------------------------
-- Source references for each Q&A pair
-- -------------------------------------------------------------------------

CREATE TABLE knowledge_source_references (
    id                 UUID        NOT NULL PRIMARY KEY,
    knowledge_qa_id    UUID        NOT NULL REFERENCES knowledge_question_answers(id) ON DELETE CASCADE,
    source_type        VARCHAR(40) NOT NULL
                                   CHECK (source_type IN (
                                       'LEGISLATION','ADMINISTRATIVE_GUIDANCE','CASE_LAW',
                                       'OFFICIAL_FAQ','INTERNAL_OPINION','ACCOUNTING_STANDARD','OTHER')),
    title              VARCHAR(500) NOT NULL,
    legal_reference    VARCHAR(500),
    url                VARCHAR(2000),
    document_id        UUID,
    fragment_id        UUID,
    valid_from         DATE,
    valid_to           DATE,
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_ksr_knowledge_qa_id ON knowledge_source_references (knowledge_qa_id);

-- -------------------------------------------------------------------------
-- Vector embeddings for RAG search
-- Requires pgvector extension (installed in V13).
-- Populated by KnowledgeQaEmbeddingIndexerImpl at publication time.
-- -------------------------------------------------------------------------

CREATE TABLE knowledge_qa_embeddings (
    id                 UUID         NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_qa_id    UUID         NOT NULL UNIQUE REFERENCES knowledge_question_answers(id) ON DELETE CASCADE,
    embedding          vector(768)  NOT NULL,
    indexed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- HNSW index for approximate nearest-neighbour cosine search
CREATE INDEX ix_kqae_embedding_hnsw
    ON knowledge_qa_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
