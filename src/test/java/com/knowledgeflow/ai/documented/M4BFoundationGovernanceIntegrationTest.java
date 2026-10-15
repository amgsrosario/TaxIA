package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingFoundation;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityRule;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityRuleRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQaGovernanceRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M4B (ADR-007) com os beans de produção sobre H2: a query nativa lê o risco e a validação humana
 * persistidos; candidatos HIGH rejeitados pelo gate de âmbito ou pela aplicabilidade não entram nos
 * fundamentos nem contaminam o risco agregado.
 */
@ActiveProfiles("test")
@SpringBootTest
class M4BFoundationGovernanceIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeQaApplicabilityRuleRepository ruleRepository;
    @Autowired private GroundingService groundingService;
    @Autowired private FoundationGovernanceResolver resolver;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Organization org;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        org = organizationRepository.save(new Organization("Org M4B", null));
    }

    @Test
    @DisplayName("Query nativa: risco e validação humana persistidos, só das Q&A pedidas")
    void nativeGovernanceQuery() {
        KnowledgeQuestionAnswer low = curated("M4B-1", "Prazo de conservação de documentos de IVA?", KnowledgeTopic.IVA,
                KnowledgeRiskLevel.LOW, true);
        KnowledgeQuestionAnswer high = curated("M4B-2", "Pensionista pode pedir retenção mensal?", KnowledgeTopic.IRS,
                KnowledgeRiskLevel.HIGH, false);

        List<KnowledgeQaGovernanceRow> rows = qaRepository.findGovernanceRowsByIdIn(org.getId(), Set.of(low.getId(), high.getId()));

        assertThat(rows).extracting(r -> UUID.fromString(r.getId())).containsExactlyInAnyOrder(low.getId(), high.getId());
        assertThat(rows).filteredOn(r -> UUID.fromString(r.getId()).equals(low.getId())).singleElement().satisfies(r -> {
            assertThat(r.getRiskLevel()).isEqualTo("LOW");
            assertThat(r.getRequiresHumanValidation()).isTrue();
        });
        assertThat(resolver.resolve(org.getId(), List.of(new GroundingFoundation(high.getId(), "x"))).aggregatedRiskLevel())
                .isEqualTo(KnowledgeRiskLevel.HIGH);
    }

    @Test
    @DisplayName("HIGH rejeitado pelo âmbito (DOMAIN_MISMATCH) não eleva o risco: agregado LOW do fundamento usado")
    void highRejectedByScope_doesNotContaminate() {
        KnowledgeQuestionAnswer vat = curated("M4B-3", "Durante quanto tempo conservar os documentos de IVA?",
                KnowledgeTopic.IVA, KnowledgeRiskLevel.LOW, true);
        KnowledgeQuestionAnswer pension = curated("M4B-4", "Pensionista pode pedir retenção mensal?",
                KnowledgeTopic.IRS, KnowledgeRiskLevel.HIGH, true);

        GroundedAIResponse grounded = groundingService.process(
                "Durante quanto tempo tenho de conservar os documentos de IVA?", null,
                List.of(qa("IVA", vat.getId()), qa("Pensões", pension.getId())));

        assertThat(grounded.foundations()).extracting(GroundingFoundation::sourceQaId).containsExactly(vat.getId());
        FoundationGovernance governance = resolver.resolve(org.getId(), grounded.foundations());
        assertThat(governance.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
        assertThat(governance.requiresHumanValidation()).isTrue();
    }

    @Test
    @DisplayName("HIGH rejeitado pela aplicabilidade (INQUILINO) não eleva o risco nem a validação humana")
    void highRejectedByApplicability_doesNotContaminate() {
        KnowledgeQuestionAnswer landlord = curated("M4B-5", "Que despesas pode o senhorio deduzir às rendas?",
                KnowledgeTopic.IRS, KnowledgeRiskLevel.HIGH, true);
        ruleRepository.save(new KnowledgeQaApplicabilityRule(landlord, "INQUILINO", "Só senhorio.", "curador"));
        KnowledgeQuestionAnswer general = curated("M4B-6", "Que despesas são dedutíveis no IRS?",
                KnowledgeTopic.IRS, KnowledgeRiskLevel.LOW, false);

        GroundedAIResponse grounded = groundingService.process(
                "Sou inquilino. Que despesas posso deduzir no IRS?", null,
                List.of(qa("Senhorio", landlord.getId()), qa("Geral", general.getId())));

        assertThat(grounded.foundations()).extracting(GroundingFoundation::sourceQaId).containsExactly(general.getId());
        FoundationGovernance governance = resolver.resolve(org.getId(), grounded.foundations());
        assertThat(governance.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
        assertThat(governance.requiresHumanValidation()).isFalse();
        assertThat(governance.decisionRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
    }

    @Test
    @DisplayName("Revisão #1: falha real da query sob Spring → risco desconhecido, sem excepção (sem rollback-only)")
    void realQueryFailure_failsClosed_withoutEscaping() {
        KnowledgeQuestionAnswer qa = curated("M4B-7", "Prazo de conservação de documentos de IVA?", KnowledgeTopic.IVA,
                KnowledgeRiskLevel.LOW, false);
        jdbc.execute("ALTER TABLE knowledge_question_answers ALTER COLUMN risk_level RENAME TO risk_level_m4b");
        try {
            FoundationGovernance governance = resolver.resolve(org.getId(), List.of(new GroundingFoundation(qa.getId(), "x")));
            assertThat(governance.riskUnknown()).isTrue();
            assertThat(governance.requiresHumanValidation()).isTrue();
            assertThat(governance.decisionRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
            assertThat(governance.foundations()).singleElement()
                    .satisfies(f -> assertThat(f.unknownReason()).contains("falha de leitura"));
        } finally {
            jdbc.execute("ALTER TABLE knowledge_question_answers ALTER COLUMN risk_level_m4b RENAME TO risk_level");
        }
    }

    @Test
    @DisplayName("Revisão #8: Q&A de outra organização não é lida → risco desconhecido (fail-closed)")
    void otherOrganization_isUnknown() {
        Organization other = organizationRepository.save(new Organization("Outra org", null));
        KnowledgeQuestionAnswer foreign = new KnowledgeQuestionAnswer(other, "Pergunta?", "Resposta.", "m4b-test", "M4B-8");
        foreign.updateCuration(null, null, null, KnowledgeTopic.IVA, null, "PT", KnowledgeRiskLevel.LOW, false,
                null, null, null);
        foreign = qaRepository.save(foreign);

        FoundationGovernance governance = resolver.resolve(org.getId(), List.of(new GroundingFoundation(foreign.getId(), "x")));
        assertThat(governance.riskUnknown()).isTrue();
        assertThat(governance.foundations()).singleElement()
                .satisfies(f -> assertThat(f.unknownReason()).isEqualTo("Q&A inexistente"));
    }

    private KnowledgeQuestionAnswer curated(String key, String question, KnowledgeTopic topic,
            KnowledgeRiskLevel risk, boolean requiresHumanValidation) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, question, "Resposta.", "m4b-test", key);
        qa.updateCuration(null, null, null, topic, null, "PT", risk, requiresHumanValidation, null, null, null);
        return qaRepository.save(qa);
    }

    private static RetrievedCase qa(String title, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta validada.", 0.95, SourceKind.KNOWLEDGE_QA, qaId);
    }
}
