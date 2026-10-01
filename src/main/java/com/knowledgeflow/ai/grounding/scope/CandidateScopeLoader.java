package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carrega, numa única query, os campos das Q&amp;A candidatas necessários para derivar o âmbito
 * (tema, subtema, pergunta). Só leitura; sem metadata persistida nova.
 */
@Component
public class CandidateScopeLoader {

    private final KnowledgeQuestionAnswerRepository repository;
    private final FiscalScopeClassifier classifier;

    public CandidateScopeLoader(KnowledgeQuestionAnswerRepository repository, FiscalScopeClassifier classifier) {
        this.repository = repository;
        this.classifier = classifier;
    }

    /**
     * Âmbito por id de Q&amp;A; ids sem linha, ou sem pergunta, não aparecem no mapa (o filtro
     * rejeita-os por falta de metadata).
     */
    @Transactional(readOnly = true)
    public Map<UUID, FiscalScope> load(Collection<UUID> qaIds) {
        Map<UUID, FiscalScope> scopes = new HashMap<>();
        if (qaIds == null || qaIds.isEmpty()) {
            return scopes;
        }
        for (KnowledgeQaScopeRow row : repository.findScopeRowsByIdIn(qaIds)) {
            String question = row.normalizedQuestion() != null && !row.normalizedQuestion().isBlank()
                    ? row.normalizedQuestion() : row.originalQuestion();
            if (question == null || question.isBlank()) {
                continue;
            }
            scopes.put(row.id(), classifier.classifyCandidate(row.topic(), row.subtopic(), question));
        }
        return scopes;
    }
}
