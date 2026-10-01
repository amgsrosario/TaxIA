package com.knowledgeflow.ai.grounding;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GroundingService {

    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);

    private static final String GROUNDING_CONSTRAINTS = """
            INSTRUÇÕES OBRIGATÓRIAS DO SISTEMA TaxIA:
            1. O contexto documental fornecido abaixo é a única fonte factual autorizada.
            2. Não cites artigos, leis, taxas, prazos, datas ou valores ausentes do contexto.
            3. Não completes lacunas com conhecimento de memória.
            4. Se o contexto não cobrir a pergunta, indica explicitamente que não tens base suficiente.
            5. Distingue os factos fornecidos da tua interpretação.
            6. Identifica os elementos em falta quando não consegues responder plenamente.
            7. Indica quando a resposta deve ser validada por um profissional qualificado.
            8. Não afirmes ter consultado fontes externas ao contexto fornecido.
            9. Não inventes fontes, artigos, taxas, prazos ou valores.
            """;

    private final ContextSufficiencyEvaluator evaluator;
    private final AnswerGroundingValidator validator;
    private final SafeResponseFactory safeResponseFactory;
    private final AIService aiService;
    private final GroundingProperties props;

    public GroundingService(
            ContextSufficiencyEvaluator evaluator,
            AnswerGroundingValidator validator,
            SafeResponseFactory safeResponseFactory,
            AIService aiService,
            GroundingProperties props) {
        this.evaluator = evaluator;
        this.validator = validator;
        this.safeResponseFactory = safeResponseFactory;
        this.aiService = aiService;
        this.props = props;
    }

    public GroundedAIResponse process(
            String question,
            String userSystemPrompt,
            List<RetrievedCase> retrievedCases) {

        // Só os candidatos relevantes seguem para a avaliação, o prompt, o validador e as fontes.
        List<RetrievedCase> relevantCases = filterByRelevance(retrievedCases);

        ContextSufficiencyAssessment assessment = evaluator.evaluate(relevantCases, question);
        log.debug("Context assessment: status={}, fragments={}, sources={}",
                assessment.status(), assessment.fragmentCount(), assessment.distinctSourceCount());

        if (!assessment.isSufficient() && props.skipProviderWhenContextInsufficient()) {
            log.info("Skipping provider: context insufficient (status={})", assessment.status());
            return safeResponseFactory.buildRefusal(assessment);
        }

        String controlledPrompt = buildControlledPrompt(userSystemPrompt, relevantCases);
        AIResponse aiResponse = aiService.complete(new AIRequest(controlledPrompt, question));

        GroundingValidationResult validation = props.enabled()
                ? validator.validate(aiResponse.content(), relevantCases)
                : GroundingValidationResult.noClaimsDetected();

        log.debug("Grounding validation: claims={}, unsupported={}, rejected={}",
                validation.allClaims().size(), validation.unsupportedClaims().size(), validation.rejected());

        if (validation.rejected()) {
            log.warn("Response rejected by grounding validator: {}", validation.rejectionReason());
            return safeResponseFactory.buildRejected(aiResponse, assessment, validation);
        }

        AnswerSupportStatus finalStatus = assessment.status();
        if (!validation.unsupportedClaims().isEmpty()) {
            finalStatus = AnswerSupportStatus.PARTIALLY_SUPPORTED;
        }

        List<AnswerSource> sources = relevantCases.stream()
                .filter(c -> c.content() != null && !c.content().isBlank())
                .collect(Collectors.toMap(
                        RetrievedCase::title,
                        c -> new AnswerSource(c.title(), c.title(), c.similarity(), c.sourceQaId()),
                        (a, b) -> a.relevanceScore() >= b.relevanceScore() ? a : b,
                        LinkedHashMap::new))
                .values().stream()
                .toList();

        boolean requiresHuman = assessment.humanReviewRequired()
                || finalStatus == AnswerSupportStatus.PARTIALLY_SUPPORTED;
        String validationMessage = null;
        if (assessment.humanReviewRequired()) {
            validationMessage = "Esta matéria requer validação por profissional fiscal qualificado.";
        } else if (finalStatus == AnswerSupportStatus.PARTIALLY_SUPPORTED) {
            validationMessage = "Algumas afirmações não foram completamente verificadas no contexto disponível.";
        }

        return new GroundedAIResponse(
                aiResponse.content(),
                finalStatus,
                assessment.reason(),
                sources,
                assessment.missingElements(),
                List.of(),
                requiresHuman,
                validationMessage,
                true,
                false,
                null,
                validation.unsupportedClaims().size(),
                aiResponse.provider(),
                aiResponse.modelUsed(),
                aiResponse.inputTokens(),
                aiResponse.outputTokens(),
                aiResponse.durationMillis());
    }

    /**
     * Filtro de relevância candidato a candidato (M4): cada caso recuperado só segue se a sua
     * similaridade for finita e {@code >= minimum-relevance-score}. NaN e infinitos são sempre
     * rejeitados. A ordem do RAG é preservada; o topK e o cálculo da similaridade não mudam.
     */
    private List<RetrievedCase> filterByRelevance(List<RetrievedCase> retrievedCases) {
        if (retrievedCases == null || retrievedCases.isEmpty()) {
            return List.of();
        }
        double threshold = props.minimumRelevanceScore();
        List<RetrievedCase> kept = retrievedCases.stream()
                .filter(c -> Double.isFinite(c.similarity()) && c.similarity() >= threshold)
                .toList();
        log.info("Relevance filter: retrieved={}, kept={}, discarded={}, threshold={}, topScore={}",
                retrievedCases.size(), kept.size(), retrievedCases.size() - kept.size(), threshold,
                retrievedCases.get(0).similarity());
        return kept;
    }

    private String buildControlledPrompt(String userSystemPrompt, List<RetrievedCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append(GROUNDING_CONSTRAINTS);

        if (userSystemPrompt != null && !userSystemPrompt.isBlank()) {
            sb.append("\n").append(userSystemPrompt);
        }

        if (!cases.isEmpty()) {
            sb.append("\n\n---\nCONTEXTO DOCUMENTAL AUTORIZADO:\n");
            for (RetrievedCase c : cases) {
                sb.append("\n[FONTE: ").append(c.title()).append("]\n");
                sb.append("Pergunta: ").append(c.question()).append("\n");
                if (c.content() != null) {
                    sb.append("Resposta validada: ").append(c.content()).append("\n");
                }
            }
            sb.append("---\nResponde exclusivamente com base nos documentos acima.");
        }

        return sb.toString();
    }
}
