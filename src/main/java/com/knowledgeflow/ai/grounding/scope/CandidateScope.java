package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Âmbito de uma Q&amp;A candidata: o derivado (v1) e as exclusões de aplicabilidade efectivas
 * declaradas editorialmente (M4-SCOPE-V2). Sem exclusões = comportamento v1.
 */
public record CandidateScope(FiscalScope scope, Set<ApplicabilityMarker> exclusions) {

    public CandidateScope {
        exclusions = Collections.unmodifiableSet(exclusions == null || exclusions.isEmpty()
                ? EnumSet.noneOf(ApplicabilityMarker.class) : EnumSet.copyOf(exclusions));
    }

    public static CandidateScope withoutExclusions(FiscalScope scope) {
        return new CandidateScope(scope, Set.of());
    }
}
