package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.InvestigationTrigger;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationService;
import com.knowledgeflow.research.service.PropositionExtractor;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Percurso Research → Evidence → Critical Memory → Structured Knowledge em PostgreSQL real e
 * descartável (Flyway V1–V20, Hibernate {@code validate}). Verificação do corpus real (vazio, com o
 * stub de embeddings); fonte oficial por fixtures reais; modelo substituído. Confirma que o
 * percurso não escreve no corpus de Q&amp;A nem nos embeddings.
 * Run: mvn verify -Ppgtest -Dit.test=ResearchPathPostgresIT
 */
@SpringBootTest(properties = "knowledgeflow.research.enabled=true")
@ActiveProfiles("pgtest")
@Testcontainers
class ResearchPathPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static final String URL_78 = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs78.aspx";
    static final String URL_999 = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs999.aspx";
    static final String ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";

    @Autowired InvestigationService service;
    @Autowired JdbcTemplate jdbc;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean AIService aiService;

    @Test
    @DisplayName("Persistência completa e rastreável em PostgreSQL; corpus de Q&A e embeddings intactos")
    void fullPathOnPostgres() {
        UUID org = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                org, "Org research IT", "PT920000099");
        long qaBefore = count("knowledge_question_answers");
        long embeddingsBefore = count("knowledge_qa_embeddings");

        when(sourceClient.fetch(URL_78)).thenReturn(FetchedPage.fetched(200, URL_78, ResearchFixtures.page(ResearchFixtures.CIRS_78)));
        when(sourceClient.fetch(URL_999)).thenReturn(FetchedPage.notFound(404, URL_999));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções"),
                new Hypothesis(new LegislationReference("CIRS", 999, null), "errada")));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(
                new ExtractedProposition(PropositionKind.RULE,
                        "As deduções à coleta do n.º 1 do artigo 78.º do CIRS aplicam-se apenas a residentes.",
                        "E1", ANCHOR, "IRS", "Residentes",
                        List.of(new ExtractedCondition(ConditionKind.CONDITION, "Residência em território português", ANCHOR))),
                new ExtractedProposition(PropositionKind.PROPOSITION, "Inventada sem âncora.", "E1",
                        "este texto não existe na evidência oficial", null, null, List.of())), List.of()));
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub",
                "Segundo o artigo 78.º do CIRS, as deduções aplicam-se apenas aos sujeitos passivos residentes. "
                        + "Recomenda-se confirmação profissional.", 0, 0, 0));

        ResearchInvestigation inv = service.investigate(org, null, "Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(inv.getTriggerReason()).isEqualTo(InvestigationTrigger.NO_RELEVANT_QA);
        assertThat(inv.getAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(inv.getParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);

        UUID id = inv.getId();
        assertThat(jdbc.queryForList("SELECT phase || '/' || outcome FROM research_steps WHERE investigation_id=? ORDER BY step_order",
                String.class, id)).containsExactly("CORPUS_CHECK/NO_RELEVANT_QA", "DISCOVERY/PROPOSED", "DISCOVERY/PROPOSED",
                "CONSULTATION/FETCHED", "CONFIRMATION/CONFIRMED", "CONSULTATION/NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT classification || '/' || promotion_status || '/' || validity_status"
                + " FROM research_evidence WHERE investigation_id=?", String.class, id))
                .isEqualTo("ACCEPTED/NOT_PROMOTED/CONSOLIDATED_AT_RETRIEVAL");
        assertThat(jdbc.queryForList("SELECT status FROM knowledge_propositions WHERE investigation_id=? ORDER BY statement",
                String.class, id)).containsExactlyInAnyOrder("CANDIDATE", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_proposition_supports s JOIN knowledge_propositions p"
                + " ON p.id = s.proposition_id JOIN research_evidence e ON e.id = s.evidence_id"
                + " WHERE p.investigation_id = ? AND e.investigation_id = ?", Long.class, id, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_proposition_conditions c JOIN knowledge_propositions p"
                + " ON p.id = c.proposition_id WHERE p.investigation_id = ? AND c.anchored", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT DISTINCT m.classification FROM critical_memory_entries m"
                + " JOIN critical_memory_history h ON h.entry_id = m.id WHERE h.investigation_id = ?", String.class, id))
                .contains("ERRONEOUS", "REJECTED", "SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = 'RESEARCH_INVESTIGATION_COMPLETED'",
                Long.class, id)).isEqualTo(1L);

        assertThat(count("knowledge_question_answers")).isEqualTo(qaBefore);
        assertThat(count("knowledge_qa_embeddings")).isEqualTo(embeddingsBefore);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
