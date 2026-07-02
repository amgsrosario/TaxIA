package com.knowledgeflow.ai.grounding;

import java.util.List;

public record GroundingValidationResult(
        List<SensitiveClaim> allClaims,
        List<SensitiveClaim> unsupportedClaims,
        boolean rejected,
        String rejectionReason
) {
    public static GroundingValidationResult noClaimsDetected() {
        return new GroundingValidationResult(List.of(), List.of(), false, null);
    }

    public static GroundingValidationResult accepted(List<SensitiveClaim> all) {
        List<SensitiveClaim> unsupported = all.stream().filter(c -> !c.supported()).toList();
        return new GroundingValidationResult(all, unsupported, false, null);
    }

    public static GroundingValidationResult rejected(
            List<SensitiveClaim> all,
            List<SensitiveClaim> unsupported,
            String reason) {
        return new GroundingValidationResult(all, unsupported, true, reason);
    }
}
