package com.knowledgeflow.ai.grounding.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.IncomeCategory;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.TaxDomain;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gate de âmbito (M4-SCOPE) sobre Q&amp;A reais em H2, com o bean de produção (activo no perfil test):
 * projecção só de leitura numa query, derivação do âmbito a partir de tema/subtema/pergunta e
 * fail-closed para uma Q&amp;A inexistente.
 */
@ActiveProfiles("test")
@SpringBootTest
class CandidateScopeLoaderIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private CandidateScopeLoader loader;
    @Autowired private FiscalScopeFilter filter;
    @Autowired private GroundingService groundingService;

    private KnowledgeQuestionAnswer pension;
    private KnowledgeQuestionAnswer vat;
    private KnowledgeQuestionAnswer uncurated;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        Organization org = organizationRepository.save(new Organization("Org Âmbito M4", null));
        pension = curated(org, "M4S-1", "Pensionista pode pedir retenção mensal?", KnowledgeTopic.IRS,
                "Retenção na fonte — categoria H", "Um pensionista pode pedir retenção mensal com taxa 0%?");
        vat = curated(org, "M4S-2", "Prazo de conservação de documentos de IVA?", KnowledgeTopic.IVA, null, null);
        uncurated = qaRepository.save(new KnowledgeQuestionAnswer(org, "Que despesas posso deduzir às rendas?",
                "Resposta.", "m4-scope-test", "M4S-3"));
    }

    @Test
    void findScopeRowsByIdIn_returnsOnlyRequestedRows_withScopeFields() {
        List<KnowledgeQaScopeRow> rows = qaRepository.findScopeRowsByIdIn(Set.of(pension.getId(), vat.getId()));

        assertThat(rows).extracting(KnowledgeQaScopeRow::id).containsExactlyInAnyOrder(pension.getId(), vat.getId());
        assertThat(rows).filteredOn(r -> r.id().equals(pension.getId())).singleElement().satisfies(r -> {
            assertThat(r.topic()).isEqualTo(KnowledgeTopic.IRS);
            assertThat(r.subtopic()).isEqualTo("Retenção na fonte — categoria H");
            assertThat(r.normalizedQuestion()).isEqualTo("Um pensionista pode pedir retenção mensal com taxa 0%?");
        });
    }

    @Test
    void load_derivesScopes_andOmitsUnknownIds() {
        UUID unknown = UUID.randomUUID();

        Map<UUID, FiscalScope> scopes = loader.load(Set.of(pension.getId(), vat.getId(), uncurated.getId(), unknown));

        assertThat(scopes).containsOnlyKeys(pension.getId(), vat.getId(), uncurated.getId());
        assertThat(scopes.get(pension.getId()).incomeCategories()).containsExactly(IncomeCategory.H);
        assertThat(scopes.get(pension.getId()).operations()).containsExactly(FiscalOperation.RETENCAO);
        assertThat(scopes.get(vat.getId()).domains()).containsExactly(TaxDomain.IVA);
        // sem curadoria: só a pergunta original conta
        assertThat(scopes.get(uncurated.getId()).incomeCategories()).containsExactly(IncomeCategory.F);
        assertThat(scopes.get(uncurated.getId()).operations()).containsExactly(FiscalOperation.DEDUCAO);
    }

    @Test
    void productionFilter_isActive_andFailClosedForUnknownQa() {
        List<RetrievedCase> kept = filter.filter("Durante quanto tempo tenho de conservar os documentos de IRS?",
                List.of(qa("IVA", vat.getId()), qa("Pensões", pension.getId()), qa("Fantasma", UUID.randomUUID())));

        assertThat(kept).isEmpty();
        assertThat(filter.filter("Posso pedir retenção na minha pensão?", List.of(qa("Pensões", pension.getId()))))
                .extracting(RetrievedCase::title).containsExactly("Pensões");
    }

    @Test
    void productionGroundingService_usesTheActiveGate() {
        GroundedAIResponse rejected = groundingService.process(
                "Durante quanto tempo tenho de conservar os documentos de IRS?", null,
                List.of(qa("IVA", vat.getId())));
        assertThat(rejected.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(rejected.providerCalled()).isFalse();
        assertThat(rejected.sources()).isEmpty();

        GroundedAIResponse kept = groundingService.process("Posso pedir retenção na minha pensão?", null,
                List.of(qa("Pensões", pension.getId())));
        assertThat(kept.sources()).extracting(s -> s.sourceQaId()).containsExactly(pension.getId());
    }

    private KnowledgeQuestionAnswer curated(Organization org, String key, String question, KnowledgeTopic topic,
            String subtopic, String normalizedQuestion) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, question, "Resposta.", "m4-scope-test", key);
        qa.updateCuration(normalizedQuestion, null, null, topic, subtopic, "PT", KnowledgeRiskLevel.MEDIUM,
                true, null, null, null);
        return qaRepository.save(qa);
    }

    private static RetrievedCase qa(String title, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta.", 0.95, SourceKind.KNOWLEDGE_QA, qaId);
    }
}
