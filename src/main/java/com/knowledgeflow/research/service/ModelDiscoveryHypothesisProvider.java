package com.knowledgeflow.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.AITaskType;
import com.knowledgeflow.research.source.LegislationReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hipóteses de descoberta pedidas ao modelo. A resposta é filtrada: só códigos do catálogo,
 * números de artigo plausíveis e no máximo {@code max} hipóteses. Falha do modelo ou resposta
 * ilegível dá lista vazia (a investigação continua com as referências explícitas da pergunta).
 */
@Component
public class ModelDiscoveryHypothesisProvider implements DiscoveryHypothesisProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryHypothesisProvider.class);

    private static final String SYSTEM_PROMPT = """
            Tarefa de DESCOBERTA para investigação fiscal portuguesa.
            Indica os artigos de códigos tributários portugueses que poderão regular a pergunta.
            Estas indicações são apenas hipóteses: serão confirmadas na fonte oficial e nunca servem
            de resposta. Não respondas à pergunta.
            Só podes indicar estes códigos: %s.
            Responde exclusivamente em JSON, sem texto adicional, no formato:
            {"hypotheses":[{"code":"CIRS","article":78,"suffix":null,"why":"motivo curto"}]}
            Indica no máximo %d hipóteses. Se não souberes, devolve {"hypotheses":[]}.
            """;

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public ModelDiscoveryHypothesisProvider(AIService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Hypothesis> propose(String question, Set<String> allowedCodes, int max) {
        if (max <= 0) return List.of();
        String content;
        try {
            content = aiService.complete(new AIRequest(AITaskType.EXTRACTION,
                    SYSTEM_PROMPT.formatted(String.join(", ", allowedCodes.stream().sorted().toList()), max),
                    question)).content();
        } catch (RuntimeException e) {
            log.warn("Descoberta pelo modelo indisponível: {}", e.getClass().getSimpleName());
            return List.of();
        }
        JsonNode root = ModelJson.firstObject(objectMapper, content).orElse(null);
        if (root == null || !root.path("hypotheses").isArray()) return List.of();

        List<Hypothesis> out = new ArrayList<>();
        for (JsonNode h : root.path("hypotheses")) {
            if (out.size() >= max) break;
            String code = ModelJson.text(h, "code");
            if (code == null || !allowedCodes.contains(code.toUpperCase(Locale.ROOT))) continue;
            int article = h.path("article").asInt(-1);
            if (article <= 0 || article > 999) continue;
            String suffix = ModelJson.text(h, "suffix");
            if (suffix != null && !suffix.matches("(?i)[A-Z]")) suffix = null;
            out.add(new Hypothesis(new LegislationReference(code, article, suffix), ModelJson.text(h, "why")));
        }
        return out;
    }
}
