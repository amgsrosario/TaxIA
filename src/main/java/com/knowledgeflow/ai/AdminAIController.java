package com.knowledgeflow.ai;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAIController {

    private final GroundingService groundingService;
    private final RagSearchService ragSearchService;
    private final AuthenticatedUserContext authenticatedUserContext;
    private final com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics;

    public AdminAIController(GroundingService groundingService, RagSearchService ragSearchService,
            AuthenticatedUserContext authenticatedUserContext,
            com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics) {
        this.groundingService = groundingService;
        this.ragSearchService = ragSearchService;
        this.authenticatedUserContext = authenticatedUserContext;
        this.metrics = metrics;
    }

    @PostMapping("/ask")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        var organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        List<RagSearchService.RetrievedCase> retrievedCases =
                ragSearchService.findSimilar(organizationId, request.question());

        GroundedAIResponse grounded =
                groundingService.process(request.question(), request.systemPrompt(), retrievedCases);

        metrics.recordGroundingOutcome(
                grounded.supportStatus() != null ? grounded.supportStatus().name() : null,
                grounded.responseRejected());

        return ResponseEntity.ok(AskResponse.from(grounded));
    }

    public record AskRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 4000) String question,
            @jakarta.validation.constraints.Size(max = 8000) String systemPrompt
    ) {}

    public record AskResponse(
            String answer,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            long durationMillis,
            String supportStatus,
            boolean requiresHumanValidation,
            String validationMessage,
            List<String> sources,
            List<String> missingInformation,
            List<String> limitations,
            boolean providerCalled,
            boolean responseRejected,
            int unsupportedClaimsCount
    ) {
        public static AskResponse from(GroundedAIResponse g) {
            List<String> sourceTitles = g.sources().stream()
                    .map(AnswerSource::title)
                    .toList();
            return new AskResponse(
                    g.answer(),
                    g.provider(),
                    g.model(),
                    g.inputTokens(),
                    g.outputTokens(),
                    g.durationMillis(),
                    g.supportStatus() != null ? g.supportStatus().name() : null,
                    g.requiresHumanValidation(),
                    g.validationMessage(),
                    sourceTitles,
                    g.missingInformation(),
                    g.limitations(),
                    g.providerCalled(),
                    g.responseRejected(),
                    g.unsupportedClaimsCount());
        }
    }
}
