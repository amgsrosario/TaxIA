package com.knowledgeflow.ai;

import com.knowledgeflow.ai.documented.AnswerProjection;
import com.knowledgeflow.ai.documented.AnswerProjectionService;
import com.knowledgeflow.ai.documented.CuratedSourceResolver;
import com.knowledgeflow.ai.documented.DocumentedTaxiaAnswer;
import com.knowledgeflow.ai.documented.DocumentedTaxiaAnswerMapper;
import com.knowledgeflow.ai.documented.VisibilityLevel;
import com.knowledgeflow.ai.documented.VisibilityLevelResolver;
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
    private final DocumentedTaxiaAnswerMapper documentedTaxiaAnswerMapper;
    private final AnswerProjectionService answerProjectionService;
    private final VisibilityLevelResolver visibilityLevelResolver;
    private final CuratedSourceResolver curatedSourceResolver;

    public AdminAIController(GroundingService groundingService, RagSearchService ragSearchService,
            AuthenticatedUserContext authenticatedUserContext,
            com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics,
            DocumentedTaxiaAnswerMapper documentedTaxiaAnswerMapper,
            AnswerProjectionService answerProjectionService,
            VisibilityLevelResolver visibilityLevelResolver,
            CuratedSourceResolver curatedSourceResolver) {
        this.groundingService = groundingService;
        this.ragSearchService = ragSearchService;
        this.authenticatedUserContext = authenticatedUserContext;
        this.metrics = metrics;
        this.documentedTaxiaAnswerMapper = documentedTaxiaAnswerMapper;
        this.answerProjectionService = answerProjectionService;
        this.visibilityLevelResolver = visibilityLevelResolver;
        this.curatedSourceResolver = curatedSourceResolver;
    }

    @PostMapping("/ask")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        // Lente de projecção resolvida centralmente (D8); admin → INTERNAL (diagnóstico
        // moderado). A autorização mantém-se no @PreAuthorize; aqui só se escolhe a vista.
        PipelineResult result = runPipeline(
                request.question(), request.systemPrompt(), visibilityLevelResolver.resolveForAdminAsk());

        return ResponseEntity.ok(
                AskResponse.from(result.grounded(), result.documentedAnswer(), result.projectedAnswer()));
    }

    /**
     * Pergunta para demonstração: o mesmo pipeline real do {@code /ask} (RAG, grounding, decisão,
     * projecção), com a lente {@code DEMO} e um contrato mínimo. Aceita apenas a pergunta — sem
     * {@code systemPrompt} nem parâmetros técnicos — e devolve apenas a projecção, sem resposta
     * documentada completa, diagnóstico, provider, modelo ou tokens.
     */
    @PostMapping("/demo/ask")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DemoAskResponse> demoAsk(@Valid @RequestBody DemoAskRequest request) {
        PipelineResult result = runPipeline(
                request.question(), null, visibilityLevelResolver.resolveForDemo());

        return ResponseEntity.ok(new DemoAskResponse(result.projectedAnswer()));
    }

    private PipelineResult runPipeline(String question, String systemPrompt, VisibilityLevel targetVisibility) {
        var organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        List<RagSearchService.RetrievedCase> retrievedCases =
                ragSearchService.findSimilar(organizationId, question);

        GroundedAIResponse grounded = groundingService.process(question, systemPrompt, retrievedCases);

        metrics.recordGroundingOutcome(
                grounded.supportStatus() != null ? grounded.supportStatus().name() : null,
                grounded.responseRejected());

        // M3: as fontes do grounding passam às fontes documentais curadas das Q&A (por sourceQaId).
        DocumentedTaxiaAnswer documentedAnswer = documentedTaxiaAnswerMapper.fromGroundedResponse(
                question, grounded, curatedSourceResolver.resolve(grounded.sources()));
        AnswerProjection projectedAnswer = answerProjectionService.project(documentedAnswer, targetVisibility);

        return new PipelineResult(grounded, documentedAnswer, projectedAnswer);
    }

    private record PipelineResult(
            GroundedAIResponse grounded,
            DocumentedTaxiaAnswer documentedAnswer,
            AnswerProjection projectedAnswer
    ) {}

    public record DemoAskRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 4000) String question
    ) {}

    public record DemoAskResponse(AnswerProjection projectedAnswer) {}

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
            int unsupportedClaimsCount,
            DocumentedTaxiaAnswer documentedAnswer,
            AnswerProjection projectedAnswer
    ) {
        public static AskResponse from(GroundedAIResponse g, DocumentedTaxiaAnswer documentedAnswer,
                AnswerProjection projectedAnswer) {
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
                    g.unsupportedClaimsCount(),
                    documentedAnswer,
                    projectedAnswer);
        }
    }
}
