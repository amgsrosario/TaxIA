package com.knowledgeflow.rag;

import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.exception.KnowledgeCaseNotFoundException;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.rag.dto.RagBatchIndexResultResponse;
import com.knowledgeflow.rag.dto.RagIndexResultResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagAdminService {

    private static final Logger log = LoggerFactory.getLogger(RagAdminService.class);

    private final KnowledgeCaseRepository knowledgeCaseRepository;
    private final CaseEmbeddingIndexer embeddingIndexer;

    public RagAdminService(KnowledgeCaseRepository knowledgeCaseRepository,
                           CaseEmbeddingIndexer embeddingIndexer) {
        this.knowledgeCaseRepository = knowledgeCaseRepository;
        this.embeddingIndexer = embeddingIndexer;
    }

    @Transactional
    public RagIndexResultResponse indexById(UUID organizationId, UUID knowledgeCaseId) {
        KnowledgeCase kc = knowledgeCaseRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(knowledgeCaseId, organizationId)
                .orElseThrow(() -> new KnowledgeCaseNotFoundException(knowledgeCaseId));

        if (kc.getStatus() != KnowledgeCaseStatus.VALIDATED) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Only VALIDATED cases can be indexed. Current status: " + kc.getStatus());
        }

        try {
            embeddingIndexer.indexValidatedCase(kc.getId(), kc.getTitle(), kc.getQuestion(), kc.getContent());
            return new RagIndexResultResponse(knowledgeCaseId, true, "Indexed successfully");
        } catch (Exception e) {
            log.error("Failed to index case {}: {}", knowledgeCaseId, e.getMessage());
            return new RagIndexResultResponse(knowledgeCaseId, false, "Indexing failed: " + e.getMessage());
        }
    }

    @Transactional
    public RagBatchIndexResultResponse indexAllValidated(UUID organizationId) {
        List<KnowledgeCase> cases = knowledgeCaseRepository.findAllByOrganizationIdAndStatus(
                organizationId, KnowledgeCaseStatus.VALIDATED);

        int indexed = 0;
        int failed = 0;

        for (KnowledgeCase kc : cases) {
            try {
                embeddingIndexer.indexValidatedCase(kc.getId(), kc.getTitle(), kc.getQuestion(), kc.getContent());
                indexed++;
            } catch (Exception e) {
                log.warn("Batch reindex failed for case {}: {}", kc.getId(), e.getMessage());
                failed++;
            }
        }

        return new RagBatchIndexResultResponse(cases.size(), indexed, failed);
    }
}
