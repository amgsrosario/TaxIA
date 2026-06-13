package com.knowledgeflow.portal;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.billing.service.EntitlementService;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionResponse;
import com.knowledgeflow.interactions.entity.AssistedInteraction;
import com.knowledgeflow.interactions.entity.AssistedInteractionMessage;
import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import com.knowledgeflow.interactions.repository.AssistedInteractionMessageRepository;
import com.knowledgeflow.interactions.repository.AssistedInteractionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal-facing assistive interaction service.
 * All operations are scoped to the authenticated client — no org user involved.
 */
@Service
public class PortalAssistedInteractionService {

    private final AssistedInteractionRepository interactionRepository;
    private final AssistedInteractionMessageRepository messageRepository;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final AIService aiService;
    private final EntitlementService entitlementService;

    public PortalAssistedInteractionService(
            AssistedInteractionRepository interactionRepository,
            AssistedInteractionMessageRepository messageRepository,
            ClientRepository clientRepository,
            OrganizationRepository organizationRepository,
            AIService aiService,
            EntitlementService entitlementService) {
        this.interactionRepository = interactionRepository;
        this.messageRepository = messageRepository;
        this.clientRepository = clientRepository;
        this.organizationRepository = organizationRepository;
        this.aiService = aiService;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public AssistedInteractionResponse create(UUID clientId, UUID organizationId, String title) {
        Organization organization = findOrganization(organizationId);
        Client client = clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        AssistedInteraction interaction = interactionRepository.save(
                new AssistedInteraction(organization, client, title));

        return toResponse(interaction);
    }

    @Transactional
    public AssistedInteractionMessageResponse ask(UUID clientId, UUID organizationId,
                                                   UUID interactionId,
                                                   AssistedInteractionAskRequest request) {
        AssistedInteraction interaction = findPortalInteraction(interactionId, organizationId, clientId);

        if (interaction.getStatus() != AssistedInteractionStatus.OPEN) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Interaction is no longer open (status: " + interaction.getStatus() + ")");
        }

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
    public Page<AssistedInteractionResponse> list(UUID clientId, UUID organizationId, Pageable pageable) {
        return interactionRepository
                .findByOrganizationIdAndClientIdOrderByCreatedAtDesc(organizationId, clientId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AssistedInteractionMessageResponse> listMessages(UUID clientId, UUID organizationId,
                                                                  UUID interactionId) {
        findPortalInteraction(interactionId, organizationId, clientId); // scope check
        return messageRepository.findByInteractionIdOrderByCreatedAtAsc(interactionId)
                .stream().map(this::toMessageResponse).toList();
    }

    // -------------------------------------------------------------------------

    private AssistedInteraction findPortalInteraction(UUID interactionId, UUID organizationId, UUID clientId) {
        return interactionRepository
                .findByIdAndOrganizationIdAndClientId(interactionId, organizationId, clientId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Interaction not found: " + interactionId));
    }

    private Organization findOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Organization not found: " + organizationId));
    }

    private AssistedInteractionResponse toResponse(AssistedInteraction i) {
        return new AssistedInteractionResponse(
                i.getId(),
                i.getOrganization().getId(),
                i.getClient().getId(),
                null, // no org user for portal-initiated interactions
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
