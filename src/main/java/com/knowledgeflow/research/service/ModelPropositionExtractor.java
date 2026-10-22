package com.knowledgeflow.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.AITaskType;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.PropositionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracção de conhecimento estruturado pelo modelo, sempre seguida de verificação literal das
 * âncoras pelo serviço de investigação. Falha do modelo ou JSON ilegível dá extracção vazia, o que
 * leva a resposta-limite por insuficiência de fundamento estruturado.
 */
@Component
public class ModelPropositionExtractor implements PropositionExtractor {

    private static final Logger log = LoggerFactory.getLogger(ModelPropositionExtractor.class);
    private static final int MAX_PROPOSITIONS = 8;

    private static final String SYSTEM_PROMPT = """
            Tarefa de ESTRUTURAÇÃO de conhecimento fiscal a partir de evidência oficial.
            Usa exclusivamente o texto das evidências E1, E2, … fornecidas. Não uses memória própria.
            Extrai as regras ou proposições do texto que sejam relevantes para a pergunta, com as
            condições de aplicação e as excepções que o próprio texto estabelece.
            Para cada proposição e para cada condição/excepção, copia em "anchor" um excerto LITERAL
            e contíguo do texto da evidência (sem alterar palavras, números ou pontuação) que a sustente.
            Indica em "conflicts" pares de proposições (índices base 0) que se contradigam.
            Responde exclusivamente em JSON, sem texto adicional:
            {"propositions":[{"kind":"RULE","statement":"...","evidence":"E1","anchor":"...",
              "scope":"...","applicability":"...",
              "conditions":[{"kind":"CONDITION","description":"...","anchor":"..."},
                            {"kind":"EXCEPTION","description":"...","anchor":"..."}]}],
             "conflicts":[{"first":0,"second":1,"note":"..."}]}
            Se o texto não permitir extrair nada relevante, devolve {"propositions":[],"conflicts":[]}.
            """;

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public ModelPropositionExtractor(AIService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Extraction extract(String question, List<EvidenceInput> evidence) {
        if (evidence.isEmpty()) return Extraction.empty();
        StringBuilder user = new StringBuilder("PERGUNTA:\n").append(question).append("\n\nEVIDÊNCIAS:\n");
        for (EvidenceInput e : evidence) {
            user.append("\n[").append(e.label()).append(" — ").append(e.legalReference()).append("]\n")
                    .append(e.content()).append('\n');
        }
        String content;
        try {
            content = aiService.complete(new AIRequest(AITaskType.EXTRACTION, SYSTEM_PROMPT, user.toString())).content();
        } catch (RuntimeException e) {
            log.warn("Estruturação pelo modelo indisponível: {}", e.getClass().getSimpleName());
            return Extraction.empty();
        }
        JsonNode root = ModelJson.firstObject(objectMapper, content).orElse(null);
        if (root == null) return Extraction.empty();

        List<ExtractedProposition> propositions = new ArrayList<>();
        for (JsonNode p : root.path("propositions")) {
            if (propositions.size() >= MAX_PROPOSITIONS) break;
            String statement = ModelJson.text(p, "statement");
            if (statement == null || statement.isBlank()) continue;
            List<ExtractedCondition> conditions = new ArrayList<>();
            for (JsonNode c : p.path("conditions")) {
                String description = ModelJson.text(c, "description");
                if (description == null || description.isBlank()) continue;
                conditions.add(new ExtractedCondition(conditionKind(ModelJson.text(c, "kind")), description,
                        nullToEmpty(ModelJson.text(c, "anchor"))));
            }
            propositions.add(new ExtractedProposition(propositionKind(ModelJson.text(p, "kind")), statement,
                    ModelJson.text(p, "evidence"), nullToEmpty(ModelJson.text(p, "anchor")),
                    ModelJson.text(p, "scope"), ModelJson.text(p, "applicability"), conditions));
        }
        List<ExtractedConflict> conflicts = new ArrayList<>();
        for (JsonNode c : root.path("conflicts")) {
            int first = c.path("first").asInt(-1);
            int second = c.path("second").asInt(-1);
            if (first >= 0 && second >= 0 && first != second
                    && first < propositions.size() && second < propositions.size()) {
                conflicts.add(new ExtractedConflict(first, second, ModelJson.text(c, "note")));
            }
        }
        return new Extraction(propositions, conflicts);
    }

    private static PropositionKind propositionKind(String raw) {
        return "PROPOSITION".equalsIgnoreCase(raw) ? PropositionKind.PROPOSITION : PropositionKind.RULE;
    }

    private static ConditionKind conditionKind(String raw) {
        return raw != null && raw.toUpperCase(Locale.ROOT).startsWith("EXC") ? ConditionKind.EXCEPTION : ConditionKind.CONDITION;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
