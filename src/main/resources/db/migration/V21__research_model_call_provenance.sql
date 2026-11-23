-- KF-M02: proveniência das chamadas ao modelo no percurso de investigação (aditiva).
-- Cada chamada ao modelo (descoberta, estruturação, redacção) fica como passo da investigação, com
-- provider, modelo, tokens, duração e resultado. Nenhum dado é alterado; nada é promovido.

ALTER TABLE research_steps
    ADD COLUMN model_provider       VARCHAR(60),
    ADD COLUMN model_name           VARCHAR(120),
    ADD COLUMN model_input_tokens   INTEGER,
    ADD COLUMN model_output_tokens  INTEGER,
    ADD COLUMN model_duration_ms    BIGINT;

ALTER TABLE research_steps DROP CONSTRAINT research_steps_phase_check;
ALTER TABLE research_steps ADD CONSTRAINT research_steps_phase_check
    CHECK (phase IN ('CORPUS_CHECK', 'DISCOVERY', 'CONSULTATION', 'CONFIRMATION',
                     'STRUCTURING', 'ANSWER_COMPOSITION'));

ALTER TABLE research_steps DROP CONSTRAINT research_steps_origin_check;
ALTER TABLE research_steps ADD CONSTRAINT research_steps_origin_check
    CHECK (origin IN ('CORPUS', 'QUESTION_REFERENCE', 'MODEL_HYPOTHESIS', 'CRITICAL_MEMORY',
                      'OFFICIAL_SOURCE', 'MODEL'));

ALTER TABLE research_steps DROP CONSTRAINT research_steps_outcome_check;
ALTER TABLE research_steps ADD CONSTRAINT research_steps_outcome_check
    CHECK (outcome IN ('PROPOSED', 'SKIPPED', 'FETCHED', 'CONFIRMED', 'NOT_CONFIRMED', 'NOT_FOUND',
                       'BLOCKED', 'FAILED', 'NO_RELEVANT_QA', 'RELEVANT_QA_FOUND',
                       'MODEL_COMPLETED', 'MODEL_UNPARSEABLE', 'MODEL_FAILED'));

-- Uma chamada ao modelo tem sempre provider e modelo identificados.
ALTER TABLE research_steps ADD CONSTRAINT ck_rs_model_call_identified
    CHECK (origin <> 'MODEL' OR (model_provider IS NOT NULL AND model_name IS NOT NULL));
