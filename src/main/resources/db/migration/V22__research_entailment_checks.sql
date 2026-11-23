-- KF-M02/ARCH-01: segunda barreira de validação de implicação jurídica (aditiva).
-- Cada proposição que passa as verificações determinísticas (presença literal, números, suporte
-- substantivo) é avaliada por um validador de implicação; a decisão final é determinística e fica
-- registada com o veredicto, as dimensões avaliadas, a passagem citada e a sua verificação na
-- evidência oficial. Nenhum dado existente é alterado; nada é promovido.

ALTER TABLE research_steps DROP CONSTRAINT research_steps_phase_check;
ALTER TABLE research_steps ADD CONSTRAINT research_steps_phase_check
    CHECK (phase IN ('CORPUS_CHECK', 'DISCOVERY', 'CONSULTATION', 'CONFIRMATION',
                     'STRUCTURING', 'ENTAILMENT', 'ANSWER_COMPOSITION'));

ALTER TABLE research_steps DROP CONSTRAINT research_steps_origin_check;
ALTER TABLE research_steps ADD CONSTRAINT research_steps_origin_check
    CHECK (origin IN ('CORPUS', 'QUESTION_REFERENCE', 'MODEL_HYPOTHESIS', 'CRITICAL_MEMORY',
                      'OFFICIAL_SOURCE', 'MODEL', 'VERIFICATION'));

CREATE TABLE research_entailment_checks (
    id                 UUID          NOT NULL PRIMARY KEY,
    investigation_id   UUID          NOT NULL REFERENCES research_investigations(id),
    proposition_id     UUID          NOT NULL REFERENCES knowledge_propositions(id),
    evidence_id        UUID          NOT NULL REFERENCES research_evidence(id),
    model_step_id      UUID                   REFERENCES research_steps(id),
    verdict            VARCHAR(20)
                                     CHECK (verdict IS NULL OR verdict IN ('SUPORTA', 'NAO_SUPORTA', 'INDETERMINADO')),
    dimensions         TEXT,
    justification      TEXT,
    cited_passage      TEXT,
    citation_verified  BOOLEAN       NOT NULL,
    decision           VARCHAR(20)   NOT NULL CHECK (decision IN ('DEMONSTRATED', 'NOT_DEMONSTRATED')),
    decision_reason    TEXT          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    -- Implicação demonstrada exige veredicto SUPORTA e passagem citada verificada na evidência.
    CONSTRAINT ck_rec_demonstrated
        CHECK (decision <> 'DEMONSTRATED' OR (verdict = 'SUPORTA' AND citation_verified))
);

CREATE INDEX ix_rec_investigation ON research_entailment_checks (investigation_id);
CREATE INDEX ix_rec_proposition ON research_entailment_checks (proposition_id);
