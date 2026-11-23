-- ADR-010-A (A3, revisão R1): a CHECK ck_rec_demonstrated de V22 (igual ao piloto, não alterável) não recusa
-- DEMONSTRATED com verdict NULL: pela lógica de três valores, "verdict = 'SUPORTA'" é UNKNOWN e a CHECK aceita a linha.
-- Esta restrição adicional exige verdict não nulo igual a SUPORTA e citação verificada para DEMONSTRATED.
-- Aditiva: não altera dados nem a CHECK de V22.

ALTER TABLE research_entailment_checks ADD CONSTRAINT ck_rec_demonstrated_requires_verdict
    CHECK (decision <> 'DEMONSTRATED'
           OR (verdict IS NOT NULL AND verdict = 'SUPORTA' AND citation_verified IS TRUE));
