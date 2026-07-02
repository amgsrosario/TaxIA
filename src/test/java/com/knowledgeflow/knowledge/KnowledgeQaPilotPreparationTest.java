package com.knowledgeflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.ImportIssue.IssueType;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Etapa 9A — proves the pilot preparation cycle with the OFFICIAL template
 * files and the 20-case synthetic set, before any real data exists.
 * All content is fictitious. Zero external calls.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class KnowledgeQaPilotPreparationTest {

    private static final Path CSV_TEMPLATE =
            Path.of("docs", "templates", "knowledge-qa-pilot-template.csv");
    private static final Path JSON_TEMPLATE =
            Path.of("docs", "templates", "knowledge-qa-pilot-template.json");
    private static final String SYNTHETIC_SET = "/pilot/synthetic-pilot-20-cases.csv";

    @Autowired private KnowledgeQuestionAnswerImportService importService;
    @Autowired private KnowledgeQuestionAnswerCurationService curationService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Organization org;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Piloto 9A", null));
        userId = UUID.randomUUID();
    }

    // =========================================================================
    // Templates oficiais
    // =========================================================================

    @Test
    @DisplayName("Template CSV oficial passa o dry-run sem linhas inválidas")
    void csvTemplate_isValid() throws IOException {
        ImportReport report = importService.importCsv(
                org.getId(), userId, "template-csv", Files.newInputStream(CSV_TEMPLATE), true, 0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.totalRows()).isEqualTo(4);
        assertThat(report.invalid()).isZero();
        // Todas as linhas indicam a acção prevista
        assertThat(report.issues().stream()
                .filter(i -> i.type() == IssueType.DRY_RUN_SKIPPED)
                .filter(i -> i.message().contains("would CREATE")))
                .hasSize(4);
        // PILOTO-0004 é HIGH: aviso de revisão humana obrigatória
        assertThat(report.warnings()).isGreaterThanOrEqualTo(1);
        assertThat(report.issues()).anyMatch(i -> i.type() == IssueType.WARNING
                && "PILOTO-0004".equals(i.externalKey()));
    }

    @Test
    @DisplayName("Template JSON oficial passa o dry-run sem linhas inválidas")
    void jsonTemplate_isValid() throws IOException {
        ImportReport report = importService.importJson(
                org.getId(), userId, "template-json", Files.newInputStream(JSON_TEMPLATE), true, 0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.totalRows()).isEqualTo(3);
        assertThat(report.invalid()).isZero();
    }

    @Test
    @DisplayName("Dry-run dos templates não persiste nada")
    void dryRun_persistsNothing() throws IOException {
        importService.importCsv(org.getId(), userId, "template-csv",
                Files.newInputStream(CSV_TEMPLATE), true, 0);
        importService.importJson(org.getId(), userId, "template-json",
                Files.newInputStream(JSON_TEMPLATE), true, 0);

        assertThat(qaRepository.findByOrganizationId(
                org.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }

    // =========================================================================
    // Conjunto sintético de 20 casos
    // =========================================================================

    @Test
    @DisplayName("Conjunto sintético: 20 linhas, duplicado exacto e conflito detectados, resumo correcto")
    void syntheticSet_importsWithExpectedReport() throws IOException {
        ImportReport report = importService.importCsv(
                org.getId(), userId, "sintetico-20", syntheticSet(), false, 0);

        assertThat(report.totalRows()).isEqualTo(20);
        assertThat(report.invalid()).isZero();
        // SINT-0019 é duplicado exacto de SINT-0001 → ignorado
        assertThat(report.duplicated()).isEqualTo(1);
        assertThat(report.imported()).isEqualTo(19);
        assertThat(report.issues()).anyMatch(i -> i.type() == IssueType.EXACT_DUPLICATE);
        // SINT-0020: mesma pergunta de SINT-0005, resposta diferente → conflito importado
        assertThat(report.issues()).anyMatch(i -> i.type() == IssueType.POTENTIAL_CONFLICT);
        // SINT-0017 (HIGH) e SINT-0018 (CRITICAL) → avisos de revisão humana
        assertThat(report.issues().stream()
                .filter(i -> i.type() == IssueType.WARNING)
                .filter(i -> i.message().contains("revisão humana")
                        || i.message().contains("revisao humana")))
                .hasSizeGreaterThanOrEqualTo(2);
        // Todos entram em quarentena — nada validado nem publicado automaticamente
        assertThat(qaRepository.findByOrganizationId(
                org.getId(), PageRequest.of(0, 50)))
                .allMatch(qa -> qa.getCurationStatus() == KnowledgeCurationStatus.IMPORTED)
                .allMatch(qa -> !qa.isPublished());
    }

    @Test
    @DisplayName("Reimportação do conjunto sintético é idempotente (sem novos registos)")
    void syntheticSet_reimportIsIdempotent() throws IOException {
        importService.importCsv(org.getId(), userId, "sintetico-20", syntheticSet(), false, 0);
        long afterFirst = qaRepository.findByOrganizationId(
                org.getId(), PageRequest.of(0, 100)).getTotalElements();

        ImportReport second = importService.importCsv(
                org.getId(), userId, "sintetico-20", syntheticSet(), false, 0);

        assertThat(second.imported()).isZero();
        assertThat(second.updated()).isEqualTo(19); // mesma externalKey → actualização de metadados
        long afterSecond = qaRepository.findByOrganizationId(
                org.getId(), PageRequest.of(0, 100)).getTotalElements();
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("Conflito por externalKey: reimportar com resposta diferente preserva o original")
    void externalKeyConflict_preservesOriginals() throws IOException {
        importService.importCsv(org.getId(), userId, "src-conf",
                csv("externalKey,question,answer\nCONF-1,Pergunta ficticia?,Resposta original."), false, 0);

        ImportReport second = importService.importCsv(org.getId(), userId, "src-conf",
                csv("externalKey,question,answer\nCONF-1,Pergunta ficticia?,RESPOSTA DIFERENTE."), false, 0);

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.issues()).anyMatch(i -> i.type() == IssueType.DUPLICATE_KEY);
        KnowledgeQuestionAnswer qa = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(org.getId(), "src-conf", "CONF-1")
                .orElseThrow();
        assertThat(qa.getOriginalAnswer()).isEqualTo("Resposta original.");
    }

    // =========================================================================
    // Erros e avisos no relatório
    // =========================================================================

    @Test
    @DisplayName("Relatório identifica erros: pergunta em falta e externalKey acima do limite")
    void report_identifiesErrors() throws IOException {
        String longKey = "K".repeat(KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_IMPORT_LENGTH + 1);
        ImportReport report = importService.importCsv(org.getId(), userId, "src-err", csv(
                "externalKey,question,answer\n"
                + "ERR-1,,Resposta sem pergunta.\n"
                + longKey + ",Pergunta ficticia?,Resposta ficticia.\n"), true, 0);

        assertThat(report.invalid()).isEqualTo(2);
        assertThat(report.issues().stream().filter(i -> i.type() == IssueType.INVALID_ROW)).hasSize(2);
        assertThat(qaRepository.count()).isZero(); // dry-run
    }

    @Test
    @DisplayName("Relatório identifica avisos: data inválida, riskLevel desconhecido, topic desconhecido")
    void report_identifiesWarnings() throws IOException {
        ImportReport report = importService.importCsv(org.getId(), userId, "src-warn", csv(
                "externalKey,question,answer,topic,riskLevel,validFrom\n"
                + "WARN-1,Pergunta ficticia A?,Resposta A.,TEMA_INEXISTENTE,ALTISSIMO,31-12-2026\n"), true, 0);

        assertThat(report.invalid()).isZero(); // avisos não bloqueiam
        assertThat(report.warnings()).isEqualTo(3);
        assertThat(report.issues().stream().filter(i -> i.type() == IssueType.WARNING))
                .anyMatch(i -> i.message().contains("validFrom"))
                .anyMatch(i -> i.message().contains("riskLevel"))
                .anyMatch(i -> i.message().contains("topic"));
    }

    @Test
    @DisplayName("Risco HIGH sem requiresHumanValidation gera aviso e a validação fica bloqueada")
    void highRiskWithoutFlag_warnsAndBlocksValidation() throws IOException {
        ImportReport report = importService.importCsv(org.getId(), userId, "src-high", csv(
                "externalKey,question,answer,riskLevel,requiresHumanValidation\n"
                + "HIGH-1,Pergunta ficticia de risco?,Resposta ficticia.,HIGH,false\n"), false, 0);

        assertThat(report.issues().stream().filter(i -> i.type() == IssueType.WARNING))
                .anyMatch(i -> i.message().contains("requiresHumanValidation"));

        // A validação é efectivamente bloqueada até corrigir a flag
        UUID qaId = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(org.getId(), "src-high", "HIGH-1")
                .orElseThrow().getId();
        curationService.markPendingReview(org.getId(), userId, qaId);
        curationService.addSource(org.getId(), userId, qaId, new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION, "Fonte ficticia", null, null, null, null, null, null, null));
        assertThatThrownBy(() -> curationService.validate(org.getId(), userId, "revisor", qaId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Fonte ausente bloqueia a validação")
    void missingSource_blocksValidation() throws IOException {
        importService.importCsv(org.getId(), userId, "src-nosrc",
                csv("externalKey,question,answer\nNOSRC-1,Pergunta ficticia?,Resposta ficticia."), false, 0);
        UUID qaId = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(org.getId(), "src-nosrc", "NOSRC-1")
                .orElseThrow().getId();
        curationService.markPendingReview(org.getId(), userId, qaId);

        assertThatThrownBy(() -> curationService.validate(org.getId(), userId, "revisor", qaId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source");
    }

    @Test
    @DisplayName("Limite de linhas por ficheiro é aplicado com erro controlado")
    void rowLimit_isEnforced() {
        StringBuilder sb = new StringBuilder("externalKey,question,answer\n");
        for (int i = 0; i < 1001; i++) {
            sb.append("LIM-").append(i).append(",Pergunta ficticia ").append(i)
                    .append("?,Resposta ficticia ").append(i).append(".\n");
        }
        assertThatThrownBy(() ->
                importService.importCsv(org.getId(), userId, "src-lim", csv(sb.toString()), true, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maximum");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InputStream syntheticSet() {
        InputStream in = getClass().getResourceAsStream(SYNTHETIC_SET);
        assertThat(in).as("conjunto sintético deve existir em src/test/resources%s", SYNTHETIC_SET)
                .isNotNull();
        return in;
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
