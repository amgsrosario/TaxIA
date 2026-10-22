package com.knowledgeflow.research.service;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.grounding.AnswerGroundingValidator;
import com.knowledgeflow.ai.grounding.GroundingValidationResult;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.enums.ConditionKind;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resposta final de uma investigação. Só recebe evidência ACCEPTED e proposições utilizáveis
 * (CANDIDATE ancoradas); nada da memória crítica chega ao prompt. O texto gerado passa pelo mesmo
 * {@link AnswerGroundingValidator} do {@code /ask}, contra a evidência recolhida: o grounding final
 * não pode introduzir factos fora desse conjunto (Aditamento §2).
 */
@Component
public class ResearchAnswerComposer {

    private static final String SYSTEM_PROMPT = """
            INSTRUÇÕES OBRIGATÓRIAS DO SISTEMA TaxIA — RESPOSTA A PARTIR DE INVESTIGAÇÃO:
            1. A evidência oficial abaixo é a única fonte factual autorizada. Foi recolhida nesta
               investigação e NÃO foi promovida a conhecimento institucional validado.
            2. Não cites artigos, leis, taxas, prazos, datas ou valores ausentes da evidência.
            3. Não completes lacunas com conhecimento de memória.
            4. Aplica as regras estruturadas indicadas e apresenta as condições e excepções como
               pressupostos que o utilizador deve confirmar no seu caso.
            5. Distingue o que a evidência diz da tua interpretação.
            6. Indica as fontes usadas pela referência legal.
            7. Termina recomendando a confirmação por profissional qualificado, porque a fundamentação
               assenta em evidência de investigação não promovida.
            """;

    private final AIService aiService;
    private final AnswerGroundingValidator validator;

    public ResearchAnswerComposer(AIService aiService, AnswerGroundingValidator validator) {
        this.aiService = aiService;
        this.validator = validator;
    }

    public record ComposedAnswer(String text, GroundingValidationResult validation, AIResponse response) {}

    public ComposedAnswer compose(String question, List<ResearchEvidence> evidence, List<KnowledgeProposition> propositions) {
        evidence.forEach(e -> {
            if (!e.isUsableAsFoundation()) throw new IllegalArgumentException("Evidência não aceite: " + e.getId());
        });
        propositions.forEach(p -> {
            if (!p.isUsableAsFoundation()) throw new IllegalArgumentException("Proposição não utilizável: " + p.getId());
        });

        StringBuilder prompt = new StringBuilder(SYSTEM_PROMPT).append("\n---\nEVIDÊNCIA OFICIAL NÃO PROMOVIDA:\n");
        for (ResearchEvidence e : evidence) {
            prompt.append("\n[FONTE: ").append(e.getLegalReference()).append(" — ").append(e.getUrl())
                    .append(" — consultada em ").append(e.getRetrievedAt().toLocalDate()).append("]\n")
                    .append(e.getContent()).append('\n');
        }
        prompt.append("\n---\nCONHECIMENTO ESTRUTURADO (extraído e ancorado na evidência acima):\n");
        int i = 1;
        for (KnowledgeProposition p : propositions) {
            prompt.append("\nR").append(i++).append(" (").append(p.getFoundation()).append("): ")
                    .append(p.getStatement()).append('\n');
            for (KnowledgeProposition.Condition c : p.getConditions()) {
                if (!c.isAnchored()) continue;
                prompt.append(c.getKind() == ConditionKind.EXCEPTION ? "  Excepção: " : "  Condição: ")
                        .append(c.getDescription()).append('\n');
            }
        }
        prompt.append("---\nResponde exclusivamente com base na evidência e nas regras acima.");

        AIResponse response = aiService.complete(new AIRequest(prompt.toString(), question));
        GroundingValidationResult validation = validator.validate(response.content(), asContext(evidence));
        return new ComposedAnswer(response.content(), validation, response);
    }

    static List<RetrievedCase> asContext(List<ResearchEvidence> evidence) {
        return evidence.stream()
                .map(e -> new RetrievedCase(e.getLegalReference(), e.getTitle(), e.getContent(), 1.0,
                        SourceKind.DOCUMENT, null))
                .toList();
    }
}
