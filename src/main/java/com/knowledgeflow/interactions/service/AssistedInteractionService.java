package com.knowledgeflow.interactions.service;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.billing.service.EntitlementService;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionCreateRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionPromoteRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionResponse;
import com.knowledgeflow.interactions.entity.AssistedInteraction;
import com.knowledgeflow.interactions.entity.AssistedInteractionMessage;
import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import com.knowledgeflow.interactions.repository.AssistedInteractionMessageRepository;
import com.knowledgeflow.interactions.repository.AssistedInteractionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistedInteractionService {

    private final AssistedInteractionRepository interactionRepository;
    private final AssistedInteractionMessageRepository messageRepository;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AIService aiService;
    private final EntitlementService entitlementService;
    private final KnowledgeCaseService knowledgeCaseService;

    public AssistedInteractionService(
            AssistedInteractionRepository interactionRepository,
            AssistedInteractionMessageRepository messageRepository,
            ClientRepository clientRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            AIService aiService,
            EntitlementService entitlementService,
            @Lazy KnowledgeCaseService knowledgeCaseService
    ) {
        this.interactionRepository = interactionRepository;
        this.messageRepository = messageRepository;
        this.clientRepository = clientRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.aiService = aiService;
        this.entitlementService = entitlementService;
        this.knowledgeCaseService = knowledgeCaseService;
    }

    @Transactional
    public AssistedInteractionResponse create(UUID organizationId, UUID userId,
                                               AssistedInteractionCreateRequest request) {
        Organization organization = findOrganization(organizationId);
        Client client = clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(
                        request.clientId(), organizationId)
                .orElseThrow(() -> new ClientNotFoundException(request.clientId()));
        User user = findUser(userId);

        AssistedInteraction interaction = interactionRepository.save(
                new AssistedInteraction(organization, client, user, request.title()));

        return toResponse(interaction);
    }

    @Transactional
    public AssistedInteractionMessageResponse ask(UUID organizationId, UUID userId,
                                                   UUID interactionId,
                                                   AssistedInteractionAskRequest request) {
        AssistedInteraction interaction = findInteraction(organizationId, interactionId);

        if (interaction.getStatus() != AssistedInteractionStatus.OPEN) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Interaction is no longer open (status: " + interaction.getStatus() + ")");
        }

        // Guard: check plan + record consumption atomically
        entitlementService.checkAndRecordInteraction(organizationId, interactionId);

        AIResponse aiResponse = aiService.complete(new AIRequest(request.question()));

        AssistedInteractionMessage message = messageRepository.save(new AssistedInteractionMessage(
                interaction,
                request.question(),
                aiResponse.content(),
                aiResponse.modelUsed(),
                aiResponse.inputTokens(),
                aiResponse.outputTokens()
        ));

        return toMessageResponse(message);
    }

    @Transactional(readOnly = true)
    public AssistedInteractionResponse get(UUID organizationId, UUID interactionId) {
        return toResponse(findInteraction(organizationId, interactionId));
    }

    @Transactional(readOnly = true)
    public Page<AssistedInteractionResponse> list(UUID organizationId, UUID clientId, Pageable pageable) {
        Page<AssistedInteraction> page = clientId != null
                ? interactionRepository.findByOrganizationIdAndClientId(organizationId, clientId, pageable)
                : interactionRepository.findByOrganizationId(organizationId, pageable);
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AssistedInteractionMessageResponse> listMessages(UUID organizationId, UUID interactionId) {
        findInteraction(organizationId, interactionId); // scope check
        return messageRepository.findByInteractionIdOrderByCreatedAtAsc(interactionId)
                .stream().map(this::toMessageResponse).toList();
    }

    @Transactional
    public KnowledgeCaseDetailResponse promote(UUID organizationId, UUID userId,
                                                UUID interactionId,
                                                AssistedInteractionPromoteRequest request) {
        AssistedInteraction interaction = findInteraction(organizationId, interactionId);

        if (interaction.getStatus() != AssistedInteractionStatus.OPEN) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Only OPEN interactions can be promoted (current status: " + interaction.getStatus() + ")");
        }

        String title = request.title() != null ? request.title() : interaction.getTitle();

        KnowledgeCaseDetailResponse knowledgeCase = knowledgeCaseService.createFromInteraction(
                organizationId, userId,
                interaction.getClient().getId(),
                title,
                request.question(),
                request.content()
        );

        interaction.promote(knowledgeCase.id());
        return knowledgeCase;
    }

    // -------------------------------------------------------------------------

    AssistedInteraction findInteraction(UUID organizationId, UUID interactionId) {
        return interactionRepository.findByIdAndOrganizationId(interactionId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Assisted interaction not found: " + interactionId));
    }

    // -------------------------------------------------------------------------

    private Organization findOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Organization not found: " + organizationId));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.FORBIDDEN,
                        "Authenticated user is invalid"));
    }

    private AssistedInteractionResponse toResponse(AssistedInteraction i) {
        return new AssistedInteractionResponse(
                i.getId(),
                i.getOrganization().getId(),
                i.getClient().getId(),
                i.getCreatedByUser().getId(),
                i.getTitle(),
                i.getStatus(),
                i.getPromotedCaseId(),
                i.getCreatedAt(),
                i.getUpdatedAt()
        );
    }

    private AssistedInteractionMessageResponse toMessageResponse(AssistedInteractionMessage m) {
        return new AssistedInteractionMessageResponse(
                m.getId(),
                m.getInteraction().getId(),
                m.getQuestion(),
                m.getAnswer(),
                m.getModelUsed(),
                m.getInputTokens(),
                m.getOutputTokens(),
                m.getCreatedAt()
        );
    }
}
