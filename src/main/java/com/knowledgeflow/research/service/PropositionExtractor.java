package com.knowledgeflow.research.service;

import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.PropositionKind;
import java.util.List;

/**
 * Estruturação: propõe regras/proposições, condições, excepções e conflitos a partir da evidência
 * aceite. A proposta não é confiável por si: cada afirmação e cada condição tem de trazer uma
 * âncora copiada literalmente da evidência, verificada por {@link AnchorVerifier}.
 */
public interface PropositionExtractor {

    /** Evidência apresentada ao extractor, identificada por {@code label} (E1, E2…). */
    record EvidenceInput(String label, String legalReference, String content) {}

    record ExtractedCondition(ConditionKind kind, String description, String anchor) {}

    record ExtractedProposition(
            PropositionKind kind,
            String statement,
            String evidenceLabel,
            String anchor,
            String scope,
            String applicability,
            List<ExtractedCondition> conditions) {}

    /** Conflito entre as proposições de índice {@code first} e {@code second} (base 0). */
    record ExtractedConflict(int first, int second, String note) {}

    record Extraction(List<ExtractedProposition> propositions, List<ExtractedConflict> conflicts) {
        public static Extraction empty() {
            return new Extraction(List.of(), List.of());
        }
    }

    Extraction extract(String question, List<EvidenceInput> evidence);
}
