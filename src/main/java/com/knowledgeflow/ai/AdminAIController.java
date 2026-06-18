package com.knowledgeflow.ai;

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

    private final AIService aiService;
    private final RagSearchService ragSearchService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public AdminAIController(AIService aiService, RagSearchService ragSearchService,
            AuthenticatedUserContext authenticatedUserContext) {
        this.aiService = aiService;
        this.ragSearchService = ragSearchService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping("/ask")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        var organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        List<RagSearchService.RetrievedCase> retrievedCases = ragSearchService.findSimilar(organizationId, request.question());

        String systemPrompt = buildSystemPrompt(request.systemPrompt(), retrievedCases);
        AIResponse response = aiService.complete(new AIRequest(systemPrompt, request.question()));
        return ResponseEntity.ok(new AskResponse(response.content(), response.provider(),
                response.modelUsed(), response.inputTokens(), response.outputTokens()));
    }

    private String buildSystemPrompt(String basePrompt, List<RagSearchService.RetrievedCase> retrievedCases) {
        if (retrievedCases.isEmpty()) {
            return basePrompt;
        }

        StringBuilder context = new StringBuilder();
        context.append("Usa os seguintes pareceres fiscais validados pela equipa como referência prioritária. ")
                .append("Se forem relevantes para a pergunta, baseia a resposta neles em vez de conhecimento genérico:\n\n");
        for (RagSearchService.RetrievedCase retrievedCase : retrievedCases) {
            context.append("---\n")
                    .append("Título: ").append(retrievedCase.title()).append('\n')
                    .append("Pergunta: ").append(retrievedCase.question()).append('\n');
            if (retrievedCase.content() != null) {
                context.append("Resposta: ").append(retrievedCase.content()).append('\n');
            }
        }
        context.append("---\n");

        return basePrompt == null || basePrompt.isBlank()
                ? context.toString()
                : basePrompt + "\n\n" + context;
    }

    public record AskRequest(
            @NotBlank String question,
            String systemPrompt
    ) {}

    public record AskResponse(
            String answer,
            String provider,
            String model,
            int inputTokens,
            int outputTokens
    ) {}
}
