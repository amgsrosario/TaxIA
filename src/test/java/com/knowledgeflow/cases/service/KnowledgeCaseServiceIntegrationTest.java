package com.knowledgeflow.cases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseUpdateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseWorkflowRequest;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.enums.KnowledgeCaseVersionSourceType;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class KnowledgeCaseServiceIntegrationTest {

    @Autowired private KnowledgeCaseService knowledgeCaseService;
    @Autowired private ClientService clientService;
    @Autowired private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;
    @Autowired private KnowledgeCaseRepository knowledgeCaseRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private KnowledgeCaseCommentRepository knowledgeCaseCommentRepository;
    @Autowired private OrganizationUserRepository organizationUserRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private ConsumptionEventRepository consumptionEventRepository;

    private Organization organization;
    private User user;
    private ClientDetailResponse client;

    @BeforeEach
    void setUp() {
        consumptionEventRepository.deleteAll();
        organizationPlanRepository.deleteAll();
        commercialPlanRepository.deleteAll();
        auditEventRepository.deleteAll();
        knowledgeCaseCommentRepository.deleteAll();
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = organizationRepository.save(new Organization("KnowledgeFlow Cases", null));
        user = userRepository.save(new User(
                "author-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Author User",
                "hash-not-used"
        ));

        // Give the org an unlimited monthly plan so existing tests can create cases freely
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited Monthly", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Parecer", null, null, null, null, null, null, null, null, null, null, null, null
        ));
    }

    @Test
    void createKnowledgeCaseStoresDraftAndInitialVersion() {
        KnowledgeCaseDetailResponse response = knowledgeCaseService.create(organization.getId(), user.getId(), new KnowledgeCaseCreateRequest(
                client.id(),
                "Enquadramento de IVA",
                "Qual o enquadramento fiscal da operacao?",
                "Rascunho inicial"
        ));

        assertThat(response.status()).isEqualTo(KnowledgeCaseStatus.DRAFT);
        assertThat(response.currentVersionNumber()).isEqualTo(1);
        assertThat(response.clientId()).isEqualTo(client.id());

        var versions = knowledgeCaseVersionRepository.findByKnowledgeCaseIdOrderByVersionNumberAsc(response.id());
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().getSourceType()).isEqualTo(KnowledgeCaseVersionSourceType.HUMAN_DRAFT);
        assertThat(versions.getFirst().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void createKnowledgeCaseRecordsAuditEvent() {
        KnowledgeCaseDetailResponse response = knowledgeCaseService.create(organization.getId(), user.getId(), new KnowledgeCaseCreateRequest(
                client.id(),
                "Parecer Auditado",
                "Pergunta auditada",
                null
        ));

        var events = auditEventRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                organization.getId(), "KnowledgeCase", response.id());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getAction().name()).isEqualTo("KNOWLEDGE_CASE_CREATED");
        assertThat(events.getFirst().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void updateDraftCreatesNewVersion() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(), new KnowledgeCaseCreateRequest(
                client.id(),
                "Parecer inicial",
                "Pergunta inicial",
                "Conteudo inicial"
        ));

        KnowledgeCaseDetailResponse updated = knowledgeCaseService.update(organization.getId(), user.getId(), created.id(), new KnowledgeCaseUpdateRequest(
                "Parecer atualizado",
                "Pergunta atualizada",
                "Conteudo atualizado"
        ));

        assertThat(updated.currentVersionNumber()).isEqualTo(2);
        assertThat(updated.title()).isEqualTo("Parecer atualizado");
        assertThat(knowledgeCaseVersionRepository.findByKnowledgeCaseIdOrderByVersionNumberAsc(created.id())).hasSize(2);
    }

    @Test
    void createRejectsClientFromAnotherOrganization() {
        Organization otherOrganization = organizationRepository.save(new Organization("Other Org", null));

        // Give the other org a plan too (needed for clientService.create)
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Other Org Plan", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(otherOrganization.getId(), plan, Instant.now().minusSeconds(60), null));

        ClientDetailResponse otherClient = clientService.create(otherOrganization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Outra Org", null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThatThrownBy(() -> knowledgeCaseService.create(organization.getId(), user.getId(), new KnowledgeCaseCreateRequest(
                otherClient.id(),
                "Parecer proibido",
                "Pergunta",
                null
        ))).isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void listVersionsRequiresKnowledgeCaseFromOrganization() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(), new KnowledgeCaseCreateRequest(
                client.id(),
                "Parecer",
                "Pergunta",
                null
        ));

        assertThat(knowledgeCaseService.listVersions(organization.getId(), created.id())).hasSize(1);
    }

    @Test
    void submitForReviewTransitionsToDraftToUnderReview() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null));

        KnowledgeCaseDetailResponse submitted = knowledgeCaseService.submitForReview(
                organization.getId(), user.getId(), created.id(), null);

        assertThat(submitted.status()).isEqualTo(KnowledgeCaseStatus.UNDER_REVIEW);
        assertThat(submitted.currentVersionNumber()).isEqualTo(2);
    }

    @Test
    void validateTransitionsToValidatedAndMarksVersion() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), created.id(), null);

        KnowledgeCaseDetailResponse validated = knowledgeCaseService.validate(
                organization.getId(), user.getId(), created.id(),
                new KnowledgeCaseWorkflowRequest("Aprovado sem reservas."));

        assertThat(validated.status()).isEqualTo(KnowledgeCaseStatus.VALIDATED);

        var versions = knowledgeCaseVersionRepository.findByKnowledgeCaseIdOrderByVersionNumberAsc(created.id());
        assertThat(versions.getLast().isValidatedVersion()).isTrue();

        var comments = knowledgeCaseCommentRepository.findByKnowledgeCaseIdOrderByCreatedAtAsc(created.id());
        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().getContent()).isEqualTo("Aprovado sem reservas.");
    }

    @Test
    void requestChangesTransitionsToChangesRequestedWithComment() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), created.id(), null);

        KnowledgeCaseDetailResponse result = knowledgeCaseService.requestChanges(
                organization.getId(), user.getId(), created.id(),
                new KnowledgeCaseWorkflowRequest("Falta referência legislativa."));

        assertThat(result.status()).isEqualTo(KnowledgeCaseStatus.CHANGES_REQUESTED);

        var comments = knowledgeCaseCommentRepository.findByKnowledgeCaseIdOrderByCreatedAtAsc(created.id());
        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().getContent()).isEqualTo("Falta referência legislativa.");
    }

    @Test
    void invalidTransitionThrowsBusinessException() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null));

        assertThatThrownBy(() -> knowledgeCaseService.validate(
                organization.getId(), user.getId(), created.id(), null))
                .isInstanceOf(com.knowledgeflow.common.error.BusinessException.class);
    }
}
