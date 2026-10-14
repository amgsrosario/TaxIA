-- C9-A: required applicability context (ADR-009, amends ADR-004 §1).
-- An applicability rule is either an exclusion (EXCLUDES — the Q&A does NOT cover a situation the
-- question shows) or a required context (REQUIRES — the Q&A only applies when the question shows
-- the situation). Same table, same governance (adding narrows scope; removing widens it and needs
-- human approval on VALIDATED/published Q&A), same closed vocabulary validated in the application.
-- Existing rows are exclusions: the default keeps their behaviour unchanged.

ALTER TABLE knowledge_qa_applicability_exclusions
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'EXCLUDES';

ALTER TABLE knowledge_qa_applicability_exclusions
    ADD CONSTRAINT ck_kqa_applicability_kind CHECK (kind IN ('EXCLUDES', 'REQUIRES'));
