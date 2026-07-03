-- AT FAQ ingestion pilot (Etapa 10A)
-- RAW layer for FAQs collected from the Portal das Finanças public FAQ pages.
-- The RAW layer is source evidence only: it is never published to the RAG
-- and never becomes a canonical answer without human curation.

-- -------------------------------------------------------------------------
-- RAW items: one row per (organization, authority, official FAQ id) version.
-- When the source content changes, the previous row is kept (superseded=TRUE)
-- and a new row is created pointing back via previous_version_id.
-- -------------------------------------------------------------------------

CREATE TABLE at_faq_raw_items (
    id                          UUID          NOT NULL PRIMARY KEY,
    organization_id             UUID          NOT NULL REFERENCES organizations(id),

    official_faq_id             VARCHAR(40)   NOT NULL,
    source_authority            VARCHAR(120)  NOT NULL,
    category                    VARCHAR(120)  NOT NULL,
    subcategory                 VARCHAR(120),

    question_raw                TEXT          NOT NULL,
    answer_raw                  TEXT          NOT NULL,

    source_url                  VARCHAR(2000) NOT NULL,
    source_title                VARCHAR(500),

    fetched_at                  TIMESTAMPTZ   NOT NULL,
    last_seen_at                TIMESTAMPTZ   NOT NULL,

    content_hash                VARCHAR(64)   NOT NULL,
    parser_version              VARCHAR(20)   NOT NULL,

    ingestion_status            VARCHAR(30)   NOT NULL
                                CHECK (ingestion_status IN (
                                    'DISCOVERED','FETCHED','PARSED','NORMALIZED',
                                    'READY_FOR_IMPORT','IMPORTED','NEEDS_REVIEW',
                                    'CHANGED_AT_SOURCE','POSSIBLY_REMOVED','FAILED')),

    detected_legal_references   TEXT,
    detected_links              TEXT,

    source_removed              BOOLEAN       NOT NULL DEFAULT FALSE,
    source_changed              BOOLEAN       NOT NULL DEFAULT FALSE,
    consecutive_miss_count      INTEGER       NOT NULL DEFAULT 0,

    error_message               TEXT,

    -- Version chain: previous row is preserved, never destroyed
    previous_version_id         UUID          REFERENCES at_faq_raw_items(id),
    superseded                  BOOLEAN       NOT NULL DEFAULT FALSE,

    -- Link to the quarantined KnowledgeQuestionAnswer created at import time
    imported_qa_id              UUID          REFERENCES knowledge_question_answers(id),

    created_at                  TIMESTAMPTZ   NOT NULL,
    updated_at                  TIMESTAMPTZ   NOT NULL,
    version                     INTEGER       NOT NULL DEFAULT 0
);

-- Uniqueness: one ACTIVE row per organization + authority + official FAQ id.
-- Superseded historical versions are excluded so the version chain is kept.
CREATE UNIQUE INDEX ux_atfaq_org_authority_faq
    ON at_faq_raw_items (organization_id, source_authority, official_faq_id)
    WHERE superseded = FALSE;

CREATE INDEX ix_atfaq_organization ON at_faq_raw_items (organization_id);
CREATE INDEX ix_atfaq_status       ON at_faq_raw_items (ingestion_status);
CREATE INDEX ix_atfaq_source_url   ON at_faq_raw_items (source_url);
CREATE INDEX ix_atfaq_last_seen    ON at_faq_raw_items (last_seen_at);

-- -------------------------------------------------------------------------
-- Ingestion runs: audit/report record per discovery, dry-run or import.
-- -------------------------------------------------------------------------

CREATE TABLE at_faq_ingestion_runs (
    id               UUID         NOT NULL PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    mode             VARCHAR(20)  NOT NULL
                     CHECK (mode IN ('DISCOVER','DRY_RUN','IMPORT')),
    status           VARCHAR(20)  NOT NULL
                     CHECK (status IN ('RUNNING','COMPLETED','FAILED','BLOCKED')),
    triggered_by     UUID,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ,
    report_json      TEXT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX ix_atfaq_runs_org     ON at_faq_ingestion_runs (organization_id);
CREATE INDEX ix_atfaq_runs_started ON at_faq_ingestion_runs (started_at);

-- -------------------------------------------------------------------------
-- Page snapshots: conditional-GET cache (ETag / Last-Modified / hash) so
-- unchanged pages are not re-downloaded and re-processed.
-- -------------------------------------------------------------------------

CREATE TABLE at_faq_page_snapshots (
    id               UUID          NOT NULL PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    url              VARCHAR(2000) NOT NULL,
    content_hash     VARCHAR(64),
    etag             VARCHAR(255),
    last_modified    VARCHAR(120),
    fetched_at       TIMESTAMPTZ   NOT NULL,
    last_seen_at     TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT ux_atfaq_snapshot_org_url UNIQUE (organization_id, url)
);
