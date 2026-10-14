package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * C8 / ADR-008 com os beans de produção (H2): só as fontes das Q&amp;A efectivamente usadas entram
 * na actualidade agregada; uma Q&amp;A descartada pelo gate de âmbito com fonte OUTDATED não contamina;
 * uma Q&amp;A válida com fonte sem verificação dá UNCERTAIN e resposta limitada.
 */
@ActiveProfiles("test")
@SpringBootTest
class SourceFreshnessPipelineIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private GroundingService groundingService;
    @Autowired private CuratedSourceResolver curatedSourceResolver;
    @Autowired private FoundationGovernanceResolver governanceResolver;
    @Autowired private DocumentedTaxiaAnswerMapper mapper;

    private Organization org;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        org = organizationRepository.save(new Organization("Org C8 pipeline", null));
    }

    private KnowledgeQuestionAnswer qa(String key, String question, KnowledgeTopic topic) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, question, "Resposta.", "c8-pipe", key);
        qa.updateCuration(null, null, null, topic, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        return qaRepository.save(qa);
    }

    private KnowledgeSourceReference source(KnowledgeQuestionAnswer qa, String title, LocalDate validTo, boolean verified) {
        KnowledgeSourceReference s = new KnowledgeSourceReference(qa, KnowledgeSourceType.LEGISLATION, title);
        s.update(KnowledgeSourceType.LEGISLATION, title, null, "https://exemplo.pt/" + qa.getExternalKey(), null, null,
                null, validTo, null);
        if (verified) {
            s.recordVerification(UUID.randomUUID(), OffsetDateTime.now(), true);
        }
        return sourceRepository.save(s);
    }

    private DocumentedTaxiaAnswer answer(String question, List<RetrievedCase> candidates) {
        GroundedAIResponse grounded = groundingService.process(question, null, candidates);
        return mapper.fromGroundedResponse(question, grounded, curatedSourceResolver.resolve(grounded.sources()),
                governanceResolver.resolve(org.getId(), grounded.foundations()));
    }

    private static RetrievedCase candidate(KnowledgeQuestionAnswer qa) {
        return new RetrievedCase(qa.getOriginalQuestion(), qa.getOriginalQuestion(), "Resposta validada.", 0.95,
                SourceKind.KNOWLEDGE_QA, qa.getId());
    }

    @Test
    @DisplayName("Fonte OUTDATED de uma Q&A descartada pelo âmbito não contamina: overall CURRENT, consulta limpa")
    void discardedCandidateSource_doesNotContaminate() {
        KnowledgeQuestionAnswer vat = qa("C8P-1", "Durante quanto tempo conservar os documentos de IVA?", KnowledgeTopic.IVA);
        source(vat, "Código do IVA — Artigo 52.º", null, true);
        KnowledgeQuestionAnswer pension = qa("C8P-2", "Pensionista pode pedir retenção mensal?", KnowledgeTopic.IRS);
        source(pension, "Código do IRS — Artigo 99.º-D", LocalDate.now(SourceFreshnessPolicy.ZONE).minusDays(1), true);

        DocumentedTaxiaAnswer answer = answer("Durante quanto tempo tenho de conservar os documentos de IVA?",
                List.of(candidate(vat), candidate(pension)));

        assertThat(answer.sources()).extracting(SourceEvidence::freshnessStatus).containsExactly(FreshnessStatus.CURRENT);
        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.CURRENT);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
    }

    @Test
    @DisplayName("Q&A válida com fonte sem verificação → UNCERTAIN → com limitações e SUGGESTED")
    void validQaWithUnverifiedSource_isLimited() {
        KnowledgeQuestionAnswer vat = qa("C8P-3", "Durante quanto tempo conservar os documentos de IVA?", KnowledgeTopic.IVA);
        source(vat, "Código do IVA — Artigo 52.º", null, false);

        DocumentedTaxiaAnswer answer = answer("Durante quanto tempo tenho de conservar os documentos de IVA?",
                List.of(candidate(vat)));

        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    @DisplayName("Fonte usada com validTo expirado → OUTDATED na resposta")
    void usedSourceWithExpiredValidTo_isOutdated() {
        KnowledgeQuestionAnswer vat = qa("C8P-4", "Durante quanto tempo conservar os documentos de IVA?", KnowledgeTopic.IVA);
        source(vat, "Código do IVA — Artigo 52.º", LocalDate.now(SourceFreshnessPolicy.ZONE).minusDays(1), true);

        DocumentedTaxiaAnswer answer = answer("Durante quanto tempo tenho de conservar os documentos de IVA?",
                List.of(candidate(vat)));

        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
    }
}
