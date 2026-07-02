package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.BenchmarkDraftCase;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates benchmark case drafts from validated Q&A pairs.
 * Drafts must be reviewed by a human before entering any official dataset.
 */
@Service
public class KnowledgeBenchmarkDraftService {

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;

    public KnowledgeBenchmarkDraftService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
    }

    /** Generate a benchmark draft from a single validated Q&A. */
    @Transactional(readOnly = true)
    public BenchmarkDraftCase generateDraft(UUID organizationId, UUID qaId) {
        KnowledgeQuestionAnswer qa = qaRepository.findById(qaId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "KnowledgeQuestionAnswer not found: " + qaId));

        if (!qa.getOrganization().getId().equals(organizationId)) {
            // Cross-organization access answers NOT_FOUND (identical to a missing id)
            // so the existence of another organization's records is never revealed.
            throw new BusinessException(ApiErrorCode.NOT_FOUND,
                    "KnowledgeQuestionAnswer not found: " + qaId);
        }
        if (qa.getCurationStatus() != KnowledgeCurationStatus.VALIDATED) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Only VALIDATED entries can generate benchmark drafts");
        }

        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(qaId);
        return buildDraft(qa, sources);
    }

    /** Generate drafts from a list of QA IDs. */
    @Transactional(readOnly = true)
    public List<BenchmarkDraftCase> generateDrafts(UUID organizationId, List<UUID> qaIds) {
        return qaIds.stream()
                .map(id -> generateDraft(organizationId, id))
                .toList();
    }

    // -------------------------------------------------------------------------

    private BenchmarkDraftCase buildDraft(KnowledgeQuestionAnswer qa,
                                          List<KnowledgeSourceReference> sources) {
        String caseId = "TAXIA-DRAFT-" + qa.getId().toString().substring(0, 8).toUpperCase();
        String title = truncate(qa.getOriginalQuestion(), 80);

        String context = buildContext(qa, sources);

        List<String> expected = buildExpectedBehaviours(qa);
        List<String> forbidden = buildForbiddenBehaviours(qa);

        String category = qa.getTopic() != null ? qa.getTopic().name() : "OUTROS";
        String difficulty = toDifficulty(qa.getRiskLevel());

        return new BenchmarkDraftCase(
                caseId,
                qa.getId(),
                title,
                qa.getOriginalQuestion(),
                context,
                expected,
                forbidden,
                qa.isRequiresHumanValidation(),
                category,
                difficulty);
    }

    private String buildContext(KnowledgeQuestionAnswer qa, List<KnowledgeSourceReference> sources) {
        StringBuilder sb = new StringBuilder();
        String answer = qa.getTechnicalAnswer() != null ? qa.getTechnicalAnswer() : qa.getShortAnswer();
        if (answer != null) {
            sb.append("Resposta validada: ").append(answer).append("\n\n");
        }
        if (!sources.isEmpty()) {
            sb.append("Fontes: ");
            sb.append(sources.stream()
                    .map(s -> s.getTitle()
                            + (s.getLegalReference() != null ? " (" + s.getLegalReference() + ")" : ""))
                    .collect(Collectors.joining("; ")));
        }
        return sb.toString().strip();
    }

    private List<String> buildExpectedBehaviours(KnowledgeQuestionAnswer qa) {
        List<String> behaviours = new ArrayList<>();
        behaviours.add("Resposta fundamentada no contexto fornecido");
        behaviours.add("Sem invenção de valores ou referências legais não presentes no contexto");
        if (qa.isRequiresHumanValidation()) {
            behaviours.add("Sinalização explícita de necessidade de validação por especialista");
        }
        if (qa.getRiskLevel() == KnowledgeRiskLevel.HIGH
                || qa.getRiskLevel() == KnowledgeRiskLevel.CRITICAL) {
            behaviours.add("Indicação de risco elevado ou limitações da resposta");
        }
        return behaviours;
    }

    private List<String> buildForbiddenBehaviours(KnowledgeQuestionAnswer qa) {
        List<String> behaviours = new ArrayList<>();
        behaviours.add("Invenção de taxas, prazos ou referências legais");
        behaviours.add("Afirmação categórica sem suporte no contexto");
        if (qa.getRiskLevel() == KnowledgeRiskLevel.CRITICAL) {
            behaviours.add("Aconselhamento definitivo sem ressalva de consulta profissional");
        }
        return behaviours;
    }

    private String toDifficulty(KnowledgeRiskLevel level) {
        if (level == null) return "MEDIUM";
        return switch (level) {
            case LOW -> "LOW";
            case MEDIUM -> "MEDIUM";
            case HIGH -> "HIGH";
            case CRITICAL -> "CRITICAL";
        };
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
