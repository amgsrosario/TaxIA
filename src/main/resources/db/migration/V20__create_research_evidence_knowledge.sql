-- KF-M01 / ADR-010: Research → Evidence → Critical Memory → Structured Knowledge (aditiva).
-- AR2-DEC-KF-2026-10-22 B1–B3 e Aditamento Operacional. Nenhuma tabela existente é alterada.
-- Nada nestas tabelas é publicado nem indexado no RAG: a evidência fica NOT_PROMOTED (CHECK) e as
-- proposições só admitem estados sem promoção (B4 bloqueada até alteração normativa).

-- -------------------------------------------------------------------------
-- Investigação desencadeada por uma pergunta
-- -------------------------------------------------------------------------

CREATE TABLE research_investigations (
    id                  UUID         NOT NULL PRIMARY KEY,
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    requested_by        UUID                  REFERENCES users(id),
    question            TEXT         NOT NULL,
    trigger_reason      VARCHAR(40)  NOT NULL
                                     CHECK (trigger_reason IN ('NO_RELEVANT_QA', 'RELEVANT_QA_EXISTS',
                                                              'CORPUS_CHECK_UNAVAILABLE')),
    status              VARCHAR(20)  NOT NULL
                                     CHECK (status IN ('OPEN', 'COMPLETED', 'NOT_STARTED', 'FAILED')),
    answer_type         VARCHAR(40)
                                     CHECK (answer_type IS NULL OR answer_type IN (
                                         'CONSULTA_DOCUMENTADA_COM_LIMITACOES', 'RESPOSTA_LIMITE')),
    parecer_requirement VARCHAR(20)
                                     CHECK (parecer_requirement IS NULL OR parecer_requirement IN ('SUGGESTED', 'REQUIRED')),
    answer_text         TEXT,
    decision_reasons    TEXT,
    limitations         TEXT,
    assumptions         TEXT,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    version             INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX ix_ri_organization ON research_investigations (organization_id, created_at);

-- -------------------------------------------------------------------------
-- Passos da investigação: descoberta, consulta e confirmação (append-only)
-- -------------------------------------------------------------------------

CREATE TABLE research_steps (
    id                UUID          NOT NULL PRIMARY KEY,
    investigation_id  UUID          NOT NULL REFERENCES research_investigations(id),
    step_order        INTEGER       NOT NULL,
    phase             VARCHAR(20)   NOT NULL
                                    CHECK (phase IN ('CORPUS_CHECK', 'DISCOVERY', 'CONSULTATION', 'CONFIRMATION')),
    origin            VARCHAR(30)   NOT NULL
                                    CHECK (origin IN ('CORPUS', 'QUESTION_REFERENCE', 'MODEL_HYPOTHESIS',
                                                      'CRITICAL_MEMORY', 'OFFICIAL_SOURCE')),
    target            VARCHAR(255),
    url               VARCHAR(2000),
    http_status       INTEGER,
    outcome           VARCHAR(30)   NOT NULL
                                    CHECK (outcome IN ('PROPOSED', 'SKIPPED', 'FETCHED', 'CONFIRMED',
                                                       'NOT_CONFIRMED', 'NOT_FOUND', 'BLOCKED', 'FAILED',
                                                       'NO_RELEVANT_QA', 'RELEVANT_QA_FOUND')),
    detail            TEXT,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    UNIQUE (investigation_id, step_order)
);

-- -------------------------------------------------------------------------
-- Evidência oficial recolhida (EVIDÊNCIA DE INVESTIGAÇÃO NÃO PROMOVIDA)
-- -------------------------------------------------------------------------

CREATE TABLE research_evidence (
    id                     UUID          NOT NULL PRIMARY KEY,
    organization_id        UUID          NOT NULL REFERENCES organizations(id),
    investigation_id       UUID          NOT NULL REFERENCES research_investigations(id),
    consultation_step_id   UUID          NOT NULL REFERENCES research_steps(id),
    source_type            VARCHAR(40)   NOT NULL
                                         CHECK (source_type IN ('LEGISLATION', 'ADMINISTRATIVE_GUIDANCE',
                                                                'OFFICIAL_FAQ', 'CASE_LAW')),
    authority              VARCHAR(60)   NOT NULL,
    reference_key          VARCHAR(120)  NOT NULL,
    legal_reference        VARCHAR(500)  NOT NULL,
    title                  VARCHAR(500)  NOT NULL,
    url                    VARCHAR(2000) NOT NULL,
    retrieved_at           TIMESTAMPTZ   NOT NULL,
    content_sha256         VARCHAR(64)   NOT NULL,
    content                TEXT          NOT NULL,
    amendment_notes        TEXT,
    validity_status        VARCHAR(30)   NOT NULL
                                         CHECK (validity_status IN ('CONSOLIDATED_AT_RETRIEVAL', 'UNCERTAIN', 'REVOKED')),
    validity_reason        TEXT          NOT NULL,
    classification         VARCHAR(30)   NOT NULL
                                         CHECK (classification IN ('ACCEPTED', 'REJECTED', 'CONTRADICTED', 'INSUFFICIENT')),
    classification_reason  TEXT          NOT NULL,
    promotion_status       VARCHAR(20)   NOT NULL DEFAULT 'NOT_PROMOTED'
                                         CHECK (promotion_status = 'NOT_PROMOTED'),
    created_at             TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_re_investigation ON research_evidence (investigation_id);
CREATE INDEX ix_re_reference     ON research_evidence (organization_id, reference_key);

-- -------------------------------------------------------------------------
-- Conhecimento estruturado (B3): proposições com fundamento, condições e excepções
-- -------------------------------------------------------------------------

CREATE TABLE knowledge_propositions (
    id                UUID          NOT NULL PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations(id),
    investigation_id  UUID          NOT NULL REFERENCES research_investigations(id),
    kind              VARCHAR(20)   NOT NULL CHECK (kind IN ('RULE', 'PROPOSITION')),
    statement         TEXT          NOT NULL,
    foundation        VARCHAR(500)  NOT NULL,
    scope             TEXT,
    applicability     TEXT,
    valid_from        DATE,
    valid_to          DATE,
    validity_status   VARCHAR(30)   NOT NULL
                                    CHECK (validity_status IN ('CONSOLIDATED_AT_RETRIEVAL', 'UNCERTAIN', 'REVOKED')),
    confidence        VARCHAR(20)   NOT NULL CHECK (confidence IN ('ANCHORED', 'UNSUPPORTED')),
    status            VARCHAR(20)   NOT NULL CHECK (status IN ('CANDIDATE', 'QUARANTINED', 'REJECTED')),
    status_reason     TEXT          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX ix_kp_investigation ON knowledge_propositions (investigation_id);

-- Fundamento: âncora literal da proposição numa evidência
CREATE TABLE knowledge_proposition_supports (
    proposition_id  UUID         NOT NULL REFERENCES knowledge_propositions(id),
    evidence_id     UUID         NOT NULL REFERENCES research_evidence(id),
    anchor_text     TEXT         NOT NULL,
    anchored        BOOLEAN      NOT NULL
);

CREATE INDEX ix_kps_proposition ON knowledge_proposition_supports (proposition_id);

-- Condições e excepções, cada uma ancorada na evidência
CREATE TABLE knowledge_proposition_conditions (
    proposition_id  UUID         NOT NULL REFERENCES knowledge_propositions(id),
    position        INTEGER      NOT NULL,
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('CONDITION', 'EXCEPTION')),
    description     TEXT         NOT NULL,
    anchor_text     TEXT         NOT NULL,
    anchored        BOOLEAN      NOT NULL,
    PRIMARY KEY (proposition_id, position)
);

-- Relações entre proposições (conflito, excepção, refinamento)
CREATE TABLE knowledge_proposition_relations (
    id                   UUID          NOT NULL PRIMARY KEY,
    from_proposition_id  UUID          NOT NULL REFERENCES knowledge_propositions(id),
    to_proposition_id    UUID          NOT NULL REFERENCES knowledge_propositions(id),
    relation             VARCHAR(30)   NOT NULL CHECK (relation IN ('CONFLICTS_WITH', 'EXCEPTION_TO', 'REFINES')),
    resolved             BOOLEAN       NOT NULL DEFAULT FALSE,
    note                 TEXT,
    created_at           TIMESTAMPTZ   NOT NULL,
    CHECK (from_proposition_id <> to_proposition_id)
);

-- -------------------------------------------------------------------------
-- Memória crítica (B1): informação errada, contraditória, insuficiente, rejeitada ou superada.
-- Nunca é fundamento positivo; permanece recuperável para investigação, auditoria e reavaliação.
-- -------------------------------------------------------------------------

CREATE TABLE critical_memory_entries (
    id                               UUID          NOT NULL PRIMARY KEY,
    organization_id                  UUID          NOT NULL REFERENCES organizations(id),
    first_investigation_id           UUID                   REFERENCES research_investigations(id),
    evidence_id                      UUID                   REFERENCES research_evidence(id),
    proposition_id                   UUID                   REFERENCES knowledge_propositions(id),
    reference_key                    VARCHAR(120),
    claim                            TEXT          NOT NULL,
    claim_sha256                     VARCHAR(64)   NOT NULL,
    classification                   VARCHAR(30)   NOT NULL
                                                   CHECK (classification IN ('ERRONEOUS', 'CONTRADICTORY',
                                                                             'INSUFFICIENT', 'REJECTED', 'SUPERSEDED')),
    reason                           TEXT          NOT NULL,
    provenance                       TEXT          NOT NULL,
    source_url                       VARCHAR(2000),
    source_version                   VARCHAR(255),
    observed_at                      TIMESTAMPTZ   NOT NULL,
    contradicted_by_evidence_id      UUID                   REFERENCES research_evidence(id),
    contradicted_by_proposition_id   UUID                   REFERENCES knowledge_propositions(id),
    status                           VARCHAR(30)   NOT NULL
                                                   CHECK (status IN ('ACTIVE', 'UNDER_REEVALUATION', 'RESOLVED')),
    occurrences                      INTEGER       NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    created_at                       TIMESTAMPTZ   NOT NULL,
    updated_at                       TIMESTAMPTZ   NOT NULL,
    version                          INTEGER       NOT NULL DEFAULT 0
);

-- Recorrência: a mesma afirmação com a mesma classificação é uma só entrada (occurrences++).
CREATE UNIQUE INDEX ux_cme_claim ON critical_memory_entries (organization_id, classification, claim_sha256);
CREATE INDEX ix_cme_reference ON critical_memory_entries (organization_id, reference_key);

CREATE TABLE critical_memory_history (
    id                   UUID          NOT NULL PRIMARY KEY,
    entry_id             UUID          NOT NULL REFERENCES critical_memory_entries(id),
    event                VARCHAR(30)   NOT NULL
                                       CHECK (event IN ('CREATED', 'RECURRED', 'RECLASSIFIED', 'STATUS_CHANGED')),
    from_classification  VARCHAR(30),
    to_classification    VARCHAR(30),
    from_status          VARCHAR(30),
    to_status            VARCHAR(30),
    reason               TEXT          NOT NULL,
    investigation_id     UUID                   REFERENCES research_investigations(id),
    actor                VARCHAR(255)  NOT NULL,
    occurred_at          TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_cmh_entry ON critical_memory_history (entry_id, occurred_at);
