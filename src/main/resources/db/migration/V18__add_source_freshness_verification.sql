-- C8 / ADR-008: verificação humana estruturada da actualidade das fontes (aditiva).
-- A actualidade (CURRENT / STABLE_BUT_OLD / UNCERTAIN / OUTDATED) é CALCULADA em runtime a partir
-- destes campos; nunca é guardada nem escolhida manualmente. Sem backfill: todas as fontes
-- existentes ficam sem verificação, ou seja, UNCERTAIN por omissão (fail-safe).

ALTER TABLE knowledge_source_references
    ADD COLUMN verified_at                    TIMESTAMPTZ,
    ADD COLUMN verified_by                    UUID REFERENCES users(id),
    ADD COLUMN consolidated_version_confirmed BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stable_matter                  BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stable_matter_reason           TEXT,
    ADD COLUMN stable_matter_marked_at        TIMESTAMPTZ,
    ADD COLUMN stable_matter_marked_by        UUID REFERENCES users(id);

-- Uma verificação tem sempre data e responsável (referência governada ao utilizador).
ALTER TABLE knowledge_source_references
    ADD CONSTRAINT ck_ksr_verification_complete
        CHECK ((verified_at IS NULL) = (verified_by IS NULL));

-- A confirmação da versão consolidada só existe dentro de uma verificação.
ALTER TABLE knowledge_source_references
    ADD CONSTRAINT ck_ksr_consolidated_needs_verification
        CHECK (NOT consolidated_version_confirmed OR verified_at IS NOT NULL);

-- Matéria estável exige justificação, data e curador.
ALTER TABLE knowledge_source_references
    ADD CONSTRAINT ck_ksr_stable_matter_justified
        CHECK (NOT stable_matter
               OR (stable_matter_reason IS NOT NULL AND length(btrim(stable_matter_reason)) > 0
                   AND stable_matter_marked_at IS NOT NULL AND stable_matter_marked_by IS NOT NULL));
