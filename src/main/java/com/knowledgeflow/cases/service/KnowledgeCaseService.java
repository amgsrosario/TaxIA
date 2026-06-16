package com.knowledgeflow.cases.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.billing.service.EntitlementService;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.cases.dto.KnowledgeCaseCommentResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseUpdateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseVersionResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseWorkflowRequest;
import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.entity.KnowledgeCaseComment;
import com.knowledgeflow.cases.entity.KnowledgeCaseVersion;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.enums.KnowledgeCaseVersionSourceType;
import com.knowledgeflow.cases.exception.KnowledgeCaseNotFoundException;
import com.knowledgeflow.cases.mapper.KnowledgeCaseMapper;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.rag.KnowledgeCaseEmbeddingIndexer;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeCaseService {

    private static final String ENTITY_TYPE = "KnowledgeCase";

    private final KnowledgeCaseRepository knowledgeCaseRepository;
    private final KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;
    private final KnowledgeCaseCommentRepository knowledgeCaseCommentRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final KnowledgeCaseMapper knowledgeCaseMapper;
    private final AuditService auditService;
    private final EntitlementService entitlementService;
    private final KnowledgeCaseEmbeddingIndexer embeddingIndexer;

    public KnowledgeCaseService(
            KnowledgeCaseRepository knowledgeCaseRepository,
            KnowledgeCaseVersionRepository knowledgeCaseVersionRepository,
            KnowledgeCaseCommentRepository knowledgeCaseCommentRepository,
            ClientRepository clientRepository,
            UserRepository userRepository,
            KnowledgeCaseMapper knowledgeCaseMapper,
            AuditService auditService,
            EntitlementService entitlementService,
            KnowledgeCaseEmbeddingIndexer embeddingIndexer
    ) {
        this.knowledgeCaseRepository = knowledgeCaseRepository;
        this.knowledgeCaseVersionRepository = knowledgeCaseVersionRepository;
        this.knowledgeCaseCommentRepository = knowledgeCaseCommentRepository;
        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.knowledgeCaseMapper = knowledgeCaseMapper;
        this.auditService = auditService;
        this.entitlementService = entitlementService;
        this.embeddingIndexer = embeddingIndexer;
    }

    @Transactional
    public KnowledgeCaseDetailResponse create(UUID organizationId, UUID userId, KnowledgeCaseCreateRequest request) {
        Client client = clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(request.clientId(), organizationId)
                .orElseThrow(() -> new ClientNotFoundException(request.clientId()));
        User user = findUser(userId);
        Organization organization = client.getOrganization();

        KnowledgeCase knowledgeCase = knowledgeCaseRepository.save(new KnowledgeCase(
                organization,
                client,
                request.title(),
                request.question(),
                normalizeBlank(request.content())
        ));

        // Guard: throws BusinessException if org has no active plan or limit is exceeded.
        // Runs inside this @Transactional so the consumption event is committed atomically.
        entitlementService.checkAndRecordCaseCreation(organizationId, knowledgeCase.getId());

        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_DRAFT, user);

        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_CREATED, ENTITY_TYPE,
                knowledgeCase.getId(), "title=" + knowledgeCase.getTitle());

        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional(readOnly = true)
    public KnowledgeCaseDetailResponse get(UUID organizationId, UUID knowledgeCaseId) {
        return knowledgeCaseMapper.toDetailResponse(findKnowledgeCase(organizationId, knowledgeCaseId));
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeCaseResponse> list(UUID organizationId, KnowledgeCaseStatus status, Pageable pageable) {
        Page<KnowledgeCase> knowledgeCases = status == null
                ? knowledgeCaseRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId, pageable)
                : knowledgeCaseRepository.findByOrganizationIdAndStatusAndDeletedAtIsNull(organizationId, status, pageable);
        return knowledgeCases.map(knowledgeCaseMapper::toResponse);
    }

    @Transactional
    public KnowledgeCaseDetailResponse update(UUID organizationId, UUID userId, UUID knowledgeCaseId, KnowledgeCaseUpdateRequest request) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        if (knowledgeCase.getStatus() != KnowledgeCaseStatus.DRAFT) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "Only draft knowledge cases can be updated");
        }

        knowledgeCase.updateDraft(request.title(), request.question(), normalizeBlank(request.content()));
        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_DRAFT, findUser(userId));

        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_UPDATED, ENTITY_TYPE,
                knowledgeCase.getId());

        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeCaseVersionResponse> listVersions(UUID organizationId, UUID knowledgeCaseId) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        return knowledgeCaseVersionRepository.findByKnowledgeCaseIdOrderByVersionNumberAsc(knowledgeCase.getId()).stream()
                .map(knowledgeCaseMapper::toVersionResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Workflow transitions
    // -------------------------------------------------------------------------

    @Transactional
    public KnowledgeCaseDetailResponse submitForReview(UUID organizationId, UUID userId, UUID knowledgeCaseId,
            KnowledgeCaseWorkflowRequest request) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        User user = findUser(userId);
        knowledgeCase.submitForReview();
        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_DRAFT, user);
        saveCommentIfPresent(knowledgeCase, user, AuditAction.KNOWLEDGE_CASE_SUBMITTED_FOR_REVIEW, request);
        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_SUBMITTED_FOR_REVIEW,
                ENTITY_TYPE, knowledgeCaseId);
        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional
    public KnowledgeCaseDetailResponse requestChanges(UUID organizationId, UUID userId, UUID knowledgeCaseId,
            KnowledgeCaseWorkflowRequest request) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        User user = findUser(userId);
        knowledgeCase.requestChanges();
        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_REVIEW, user);
        saveCommentIfPresent(knowledgeCase, user, AuditAction.KNOWLEDGE_CASE_CHANGES_REQUESTED, request);
        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_CHANGES_REQUESTED,
                ENTITY_TYPE, knowledgeCaseId);
        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional
    public KnowledgeCaseDetailResponse validate(UUID organizationId, UUID userId, UUID knowledgeCaseId,
            KnowledgeCaseWorkflowRequest request) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        User user = findUser(userId);
        knowledgeCase.validate();
        KnowledgeCaseVersion version = createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.VALIDATED_FINAL, user);
        version.markAsValidated();
        saveCommentIfPresent(knowledgeCase, user, AuditAction.KNOWLEDGE_CASE_VALIDATED, request);
        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_VALIDATED,
                ENTITY_TYPE, knowledgeCaseId);
        embeddingIndexer.indexValidatedCase(knowledgeCase.getId(), knowledgeCase.getTitle(),
                knowledgeCase.getQuestion(), knowledgeCase.getContent());
        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional
    public KnowledgeCaseDetailResponse reject(UUID organizationId, UUID userId, UUID knowledgeCaseId,
            KnowledgeCaseWorkflowRequest request) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        User user = findUser(userId);
        knowledgeCase.reject();
        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_REVIEW, user);
        saveCommentIfPresent(knowledgeCase, user, AuditAction.KNOWLEDGE_CASE_REJECTED, request);
        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_REJECTED,
                ENTITY_TYPE, knowledgeCaseId);
        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    /**
     * Creates a KnowledgeCase from an existing AssistedInteraction (promotion flow).
     * Does NOT consume an entitlement — promotion is not a new billable case creation.
     * The caller (AssistedInteractionService) is responsible for marking the interaction as PROMOTED.
     */
    @Transactional
    public KnowledgeCaseDetailResponse createFromInteraction(
            UUID organizationId, UUID userId,
            UUID clientId, String title, String question, String content) {

        Client client = clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        User user = findUser(userId);
        Organization organization = client.getOrganization();

        KnowledgeCase knowledgeCase = knowledgeCaseRepository.save(new KnowledgeCase(
                organization, client, title, question, normalizeBlank(content)));

        createVersion(knowledgeCase, KnowledgeCaseVersionSourceType.HUMAN_DRAFT, user);

        auditService.record(organizationId, userId, AuditAction.KNOWLEDGE_CASE_CREATED, ENTITY_TYPE,
                knowledgeCase.getId(), "promoted=true,title=" + knowledgeCase.getTitle());

        return knowledgeCaseMapper.toDetailResponse(knowledgeCase);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeCaseCommentResponse> listComments(UUID organizationId, UUID knowledgeCaseId) {
        KnowledgeCase knowledgeCase = findKnowledgeCase(organizationId, knowledgeCaseId);
        return knowledgeCaseCommentRepository.findByKnowledgeCaseIdOrderByCreatedAtAsc(knowledgeCase.getId()).stream()
                .map(knowledgeCaseMapper::toCommentResponse)
                .toList();
    }

    private KnowledgeCase findKnowledgeCase(UUID organizationId, UUID knowledgeCaseId) {
        return knowledgeCaseRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(knowledgeCaseId, organizationId)
                .orElseThrow(() -> new KnowledgeCaseNotFoundException(knowledgeCaseId));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.FORBIDDEN, "Authenticated user is invalid"));
    }

    private KnowledgeCaseVersion createVersion(KnowledgeCase knowledgeCase, KnowledgeCaseVersionSourceType sourceType, User user) {
        KnowledgeCaseVersion version = new KnowledgeCaseVersion(knowledgeCase, knowledgeCase.nextVersionNumber(), sourceType, user);
        return knowledgeCaseVersionRepository.save(version);
    }

    private void saveCommentIfPresent(KnowledgeCase knowledgeCase, User user, AuditAction action,
            KnowledgeCaseWorkflowRequest request) {
        if (request != null && request.comment() != null && !request.comment().isBlank()) {
            knowledgeCaseCommentRepository.save(
                    new KnowledgeCaseComment(knowledgeCase, user, action, request.comment()));
        }
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
