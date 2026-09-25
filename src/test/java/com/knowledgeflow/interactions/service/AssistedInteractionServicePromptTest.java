package com.knowledgeflow.interactions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.taxia.TaxiaSystemPrompt;
import com.knowledgeflow.billing.service.EntitlementService;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.entity.AssistedInteraction;
import com.knowledgeflow.interactions.entity.AssistedInteractionMessage;
import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import com.knowledgeflow.interactions.repository.AssistedInteractionMessageRepository;
import com.knowledgeflow.interactions.repository.AssistedInteractionRepository;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * F1.1 anti-regression: the org-facing assisted-interaction caller must supply the TaxIA fiscal
 * system prompt explicitly, now that the AI providers are domain-neutral and no longer inject it.
 */
class AssistedInteractionServicePromptTest {

    @Test
    void askSuppliesTaxiaFiscalSystemPromptExplicitly() {
        AssistedInteractionRepository interactionRepository = mock(AssistedInteractionRepository.class);
        AssistedInteractionMessageRepository messageRepository = mock(AssistedInteractionMessageRepository.class);
        AIService aiService = mock(AIService.class);
        EntitlementService entitlementService = mock(EntitlementService.class);

        AssistedInteractionService service = new AssistedInteractionService(
                interactionRepository,
                messageRepository,
                mock(ClientRepository.class),
                mock(OrganizationRepository.class),
                mock(UserRepository.class),
                aiService,
                entitlementService,
                mock(KnowledgeCaseService.class));

        UUID organizationId = UUID.randomUUID();
        UUID interactionId = UUID.randomUUID();

        AssistedInteraction interaction = mock(AssistedInteraction.class);
        when(interaction.getStatus()).thenReturn(AssistedInteractionStatus.OPEN);
        when(interaction.getId()).thenReturn(interactionId);
        when(interactionRepository.findByIdAndOrganizationId(interactionId, organizationId))
                .thenReturn(Optional.of(interaction));

        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("stub", "stub-model", "resposta", 1, 1, 1L));

        AssistedInteractionMessage saved = mock(AssistedInteractionMessage.class);
        when(saved.getInteraction()).thenReturn(interaction);
        when(saved.getCreatedAt()).thenReturn(Instant.now());
        when(messageRepository.save(any(AssistedInteractionMessage.class))).thenReturn(saved);

        service.ask(organizationId, UUID.randomUUID(), interactionId,
                new AssistedInteractionAskRequest("Pergunta fiscal"));

        ArgumentCaptor<AIRequest> captor = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiService).complete(captor.capture());
        assertThat(captor.getValue().systemPrompt()).isEqualTo(TaxiaSystemPrompt.FISCAL_ASSISTANT);
        assertThat(captor.getValue().userMessage()).isEqualTo("Pergunta fiscal");
    }
}
