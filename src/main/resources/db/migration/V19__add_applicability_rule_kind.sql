-- C9-A: unified applicability rules (ADR-009; amends ADR-004 §1).
-- An applicability rule is either an exclusion (EXCLUDES — the Q&A does NOT cover a situation the
-- question shows) or a required context (REQUIRES — the Q&A only applies when the question affirms
-- the situation). One table, one governance (adding narrows scope; removing widens it and needs human
-- approval on VALIDATED/published Q&A), one closed vocabulary validated in the application.
-- The V16 table is renamed (not copied): existing rows keep id, marker, note, authorship and pending
-- removal requests, and become EXCLUDES through the column default. No Q&A gains a REQUIRES rule.

ALTER TABLE knowledge_qa_applicability_exclusions RENAME TO knowledge_qa_applicability_rules;

ALTER TABLE knowledge_qa_applicability_rules
    RENAME CONSTRAINT knowledge_qa_applicability_exclusions_pkey TO knowledge_qa_applicability_rules_pkey;
ALTER TABLE knowledge_qa_applicability_rules
    RENAME CONSTRAINT knowledge_qa_applicability_exclusions_knowledge_qa_id_fkey
        TO knowledge_qa_applicability_rules_knowledge_qa_id_fkey;
-- UNIQUE(knowledge_qa_id, marker) covers both kinds: the same marker cannot be both excluded and
-- required on the same Q&A.
ALTER TABLE knowledge_qa_applicability_rules
    RENAME CONSTRAINT ux_kqa_applicability_marker TO ux_kqa_applicability_rule_marker;

ALTER TABLE knowledge_qa_applicability_rules
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'EXCLUDES';

ALTER TABLE knowledge_qa_applicability_rules
    ADD CONSTRAINT ck_kqa_applicability_rule_kind CHECK (kind IN ('EXCLUDES', 'REQUIRES'));
