package com.knowledgeflow.portal;

import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for the client portal. Clients see only their own VALIDATED cases.
 */
@Service
public class PortalKnowledgeCaseService {

    private final KnowledgeCaseRepository knowledgeCaseRepository;

    public PortalKnowledgeCaseService(KnowledgeCaseRepository knowledgeCaseRepository) {
        this.knowledgeCaseRepository = knowledgeCaseRepository;
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeCaseResponse> list(UUID clientId, UUID organizationId, Pageable pageable) {
        return knowledgeCaseRepository
                .findByClientIdAndOrganizationIdAndStatusAndDeletedAtIsNull(
                        clientId, organizationId, KnowledgeCaseStatus.VALIDATED, pageable)
                .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public KnowledgeCaseDetailResponse get(UUID clientId, UUID organizationId, UUID caseId) {
        KnowledgeCase kc = knowledgeCaseRepository
                .findByIdAndClientIdAndOrganizationIdAndStatusAndDeletedAtIsNull(
                        caseId, clientId, organizationId, KnowledgeCaseStatus.VALIDATED)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Case not found: " + caseId));
        return toDetail(kc);
    }

    // -------------------------------------------------------------------------

    private KnowledgeCaseResponse toSummary(KnowledgeCase kc) {
        return new KnowledgeCaseResponse(
                kc.getId(),
                kc.getOrganization().getId(),
                kc.getClient().getId(),
                kc.getTitle(),
                kc.getStatus(),
                kc.getCurrentVersionNumber(),
                kc.getCreatedAt(),
                kc.getUpdatedAt()
        );
    }

    private KnowledgeCaseDetailResponse toDetail(KnowledgeCase kc) {
        return new KnowledgeCaseDetailResponse(
                kc.getId(),
                kc.getOrganization().getId(),
                kc.getClient().getId(),
                kc.getTitle(),
                kc.getQuestion(),
                kc.getContent(),
                kc.getStatus(),
                kc.getCurrentVersionNumber(),
                kc.getCreatedAt(),
                kc.getUpdatedAt()
        );
    }
}
