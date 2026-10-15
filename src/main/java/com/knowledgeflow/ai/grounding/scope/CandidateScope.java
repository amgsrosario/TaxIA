package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Âmbito de uma Q&amp;A candidata: o derivado (v1), as exclusões de aplicabilidade efectivas
 * (M4-SCOPE-V2, ADR-004) e os contextos obrigatórios efectivos (ADR-009), declarados
 * editorialmente. Sem regras = comportamento v1.
 */
public record CandidateScope(FiscalScope scope, Set<ApplicabilityMarker> exclusions,
        Set<ApplicabilityMarker> requiredContext) {

    public CandidateScope {
        exclusions = immutable(exclusions);
        requiredContext = immutable(requiredContext);
    }

    public CandidateScope(FiscalScope scope, Set<ApplicabilityMarker> exclusions) {
        this(scope, exclusions, Set.of());
    }

    public static CandidateScope withoutExclusions(FiscalScope scope) {
        return new CandidateScope(scope, Set.of(), Set.of());
    }

    private static Set<ApplicabilityMarker> immutable(Set<ApplicabilityMarker> markers) {
        return Collections.unmodifiableSet(markers == null || markers.isEmpty()
                ? EnumSet.noneOf(ApplicabilityMarker.class) : EnumSet.copyOf(markers));
    }
}
