package com.knowledgeflow.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.rag.dto.RagBatchIndexResultResponse;
import com.knowledgeflow.rag.dto.RagIndexResultResponse;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class RagAdminServiceIntegrationTest {

    @MockBean
    private CaseEmbeddingIndexer embeddingIndexer;

    @Autowired private RagAdminService ragAdminService;
    @Autowired private KnowledgeCaseService knowledgeCaseService;
    @Autowired private ClientService clientService;

    @Autowired private ConsumptionEventRepository consumptionEventRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private KnowledgeCaseCommentRepository knowledgeCaseCommentRepository;
    @Autowired private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;
    @Autowired private KnowledgeCaseRepository knowledgeCaseRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private OrganizationUserRepository organizationUserRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Organization organization;
    private User user;
    private ClientDetailResponse client;

    @BeforeEach
    void setUp() {
        consumptionEventRepository.deleteAll();
        organizationPlanRepository.deleteAll();
        commercialPlanRepository.deleteAll();
        knowledgeCaseCommentRepository.deleteAll();
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = organizationRepository.save(new Organization("RAG Admin Test Org", null));
        user = userRepository.save(new User(
                "admin-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Admin User", "hash-not-used"));

        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Cliente RAG", null, null, null, null,
                        null, null, null, null, null, null, null, null));

        doNothing().when(embeddingIndexer).indexValidatedCase(any(), anyString(), anyString(), anyString());
    }

    private KnowledgeCaseDetailResponse createValidatedCase(String title) {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), title, "Pergunta", "Conteúdo"));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), created.id(), null);
        knowledgeCaseService.validate(organization.getId(), user.getId(), created.id(), null);
        return created;
    }

    // ── indexById ────────────────────────────────────────────────────────────

    @Test
    void indexByIdSucceedsForValidatedCase() {
        KnowledgeCaseDetailResponse validated = createValidatedCase("Parecer Validado");

        RagIndexResultResponse result = ragAdminService.indexById(organization.getId(), validated.id());

        assertThat(result.knowledgeCaseId()).isEqualTo(validated.id());
        assertThat(result.indexed()).isTrue();
        verify(embeddingIndexer, times(2)).indexValidatedCase(any(), anyString(), anyString(), anyString());
        // once during validate(), once during explicit indexById
    }

    @Test
    void indexByIdRejectsNonValidatedCase() {
        KnowledgeCaseDetailResponse draft = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Rascunho", "Pergunta", null));

        assertThatThrownBy(() -> ragAdminService.indexById(organization.getId(), draft.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    void indexByIdThrowsForUnknownCase() {
        assertThatThrownBy(() -> ragAdminService.indexById(organization.getId(), UUID.randomUUID()))
                .isInstanceOf(com.knowledgeflow.cases.exception.KnowledgeCaseNotFoundException.class);
    }

    @Test
    void indexByIdRejectsOrganizationMismatch() {
        Organization otherOrg = organizationRepository.save(new Organization("Outra Org", null));
        KnowledgeCaseDetailResponse validated = createValidatedCase("Caso Válido");

        assertThatThrownBy(() -> ragAdminService.indexById(otherOrg.getId(), validated.id()))
                .isInstanceOf(com.knowledgeflow.cases.exception.KnowledgeCaseNotFoundException.class);
    }

    @Test
    void indexByIdReturnsFalseWhenIndexerThrows() {
        KnowledgeCaseDetailResponse validated = createValidatedCase("Caso com Erro");
        doThrow(new RuntimeException("Embedding service unavailable"))
                .when(embeddingIndexer).indexValidatedCase(any(), anyString(), anyString(), anyString());

        RagIndexResultResponse result = ragAdminService.indexById(organization.getId(), validated.id());

        assertThat(result.indexed()).isFalse();
        assertThat(result.message()).contains("Embedding service unavailable");
    }

    // ── indexAllValidated ────────────────────────────────────────────────────

    @Test
    void indexAllValidatedIndexesAllValidatedCases() {
        createValidatedCase("Caso 1");
        createValidatedCase("Caso 2");
        // One draft case — should not be indexed
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Rascunho", "Pergunta", null));

        doNothing().when(embeddingIndexer).indexValidatedCase(any(), anyString(), anyString(), anyString());

        RagBatchIndexResultResponse result = ragAdminService.indexAllValidated(organization.getId());

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void indexAllValidatedIsIdempotent() {
        createValidatedCase("Caso Idempotente");

        doNothing().when(embeddingIndexer).indexValidatedCase(any(), anyString(), anyString(), anyString());

        RagBatchIndexResultResponse first = ragAdminService.indexAllValidated(organization.getId());
        RagBatchIndexResultResponse second = ragAdminService.indexAllValidated(organization.getId());

        assertThat(first.total()).isEqualTo(1);
        assertThat(second.total()).isEqualTo(1);
        assertThat(first.indexed()).isEqualTo(1);
        assertThat(second.indexed()).isEqualTo(1);
    }

    @Test
    void indexAllValidatedContinuesOnPartialFailure() {
        createValidatedCase("Caso OK");
        createValidatedCase("Caso Falha");

        doNothing()
                .doThrow(new RuntimeException("fail"))
                .when(embeddingIndexer).indexValidatedCase(any(), anyString(), anyString(), anyString());

        RagBatchIndexResultResponse result = ragAdminService.indexAllValidated(organization.getId());

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void indexAllValidatedReturnsZeroWhenNoCases() {
        RagBatchIndexResultResponse result = ragAdminService.indexAllValidated(organization.getId());

        assertThat(result.total()).isEqualTo(0);
        assertThat(result.indexed()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        verify(embeddingIndexer, never()).indexValidatedCase(any(), anyString(), anyString(), anyString());
    }
}
