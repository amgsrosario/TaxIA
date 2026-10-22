package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.research.controller.ResearchTraceView;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationService;
import com.knowledgeflow.research.service.InvestigationTraceService;
import com.knowledgeflow.research.service.PropositionExtractor;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * Demonstração com a fonte oficial real (KF-M01). Opt-in: só corre com {@code -Dkfm01.live=true}.
 * <p>
 * Consulta read-only o Portal das Finanças pelo cliente real com allowlist (2 pedidos, sem
 * autenticação), numa base PostgreSQL descartável. A descoberta, a estruturação e a redacção pelo
 * modelo são substituídas por respostas fixas (sem chave de API); a confirmação, a evidência, a
 * vigência, as âncoras, a memória crítica e a decisão da resposta são as reais. A rastreabilidade
 * completa fica em {@code target/kf-m01-live-trace.json}.
 * Run: mvn verify -Ppgtest -Dit.test=ResearchLiveOfficialSourceIT -Dkfm01.live=true
 */
@EnabledIfSystemProperty(named = "kfm01.live", matches = "true")
@SpringBootTest(properties = {"knowledgeflow.research.enabled=true", "knowledgeflow.research.delay-ms=1500"})
@ActiveProfiles("pgtest")
@Testcontainers
class ResearchLiveOfficialSourceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static final String ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean AIService aiService;

    @Test
    @DisplayName("Pergunta inédita → consulta real ao Portal das Finanças → evidência, memória crítica, conhecimento e resposta")
    void liveOfficialSource() throws Exception {
        UUID org = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                org, "Org live demo", "PT920000077");
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "Deduções à coleta"),
                new Hypothesis(new LegislationReference("CIRS", 999, null), "Hipótese deliberadamente errada")));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(
                new ExtractedProposition(PropositionKind.RULE,
                        "As deduções à coleta previstas no n.º 1 do artigo 78.º do CIRS aplicam-se apenas a sujeitos "
                                + "passivos residentes em território português.",
                        "E1", ANCHOR, "IRS — deduções à coleta", "Sujeitos passivos residentes",
                        List.of(new ExtractedCondition(ConditionKind.CONDITION,
                                "O sujeito passivo é residente em território português", ANCHOR))),
                new ExtractedProposition(PropositionKind.PROPOSITION,
                        "Os não residentes podem deduzir despesas de saúde até 1 000 euros.", "E1",
                        "os não residentes podem deduzir despesas de saúde", null, null, List.of())), List.of()));
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub",
                "Não. Segundo o artigo 78.º do CIRS, as deduções previstas no n.º 1 aplicam-se apenas aos sujeitos "
                        + "passivos residentes em território português. Pressuposto a confirmar: a residência fiscal. "
                        + "Recomenda-se a confirmação por profissional qualificado.", 0, 0, 0));

        ResearchInvestigation inv = service.investigate(org, null,
                "Um contribuinte não residente em Portugal pode fazer deduções à coleta no IRS?");
        ResearchTraceView view = ResearchTraceView.from(traceService.trace(org, inv.getId()));

        Path out = Path.of("target", "kf-m01-live-trace.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(view));

        assertThat(view.evidence()).singleElement().satisfies(e -> {
            assertThat(e.url()).startsWith("https://info.portaldasfinancas.gov.pt/");
            assertThat(e.classification()).isEqualTo("ACCEPTED");
            assertThat(e.promotionStatus()).isEqualTo("NOT_PROMOTED");
            assertThat(e.content()).contains(ANCHOR);
        });
        assertThat(view.steps()).anySatisfy(s -> assertThat(s.outcome()).isEqualTo("CONFIRMED"));
        assertThat(view.steps()).anySatisfy(s -> {
            assertThat(s.target()).isEqualTo("artigo 999.º do CIRS");
            assertThat(s.outcome()).isIn("NOT_FOUND", "BLOCKED", "FAILED");
        });
        assertThat(view.criticalMemory()).anySatisfy(c -> assertThat(c.claim()).contains("1 000 euros"));
        assertThat(inv.getAnswerType()).isIn(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES, AnswerType.RESPOSTA_LIMITE);
    }
}
