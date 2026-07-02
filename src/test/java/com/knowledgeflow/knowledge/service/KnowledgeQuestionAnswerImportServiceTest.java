package com.knowledgeflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class KnowledgeQuestionAnswerImportServiceTest {

    @Autowired private KnowledgeQuestionAnswerImportService importService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private Organization org;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("TaxIA Import Test", null));
        userId = UUID.randomUUID();
    }

    // 1. CSV válido
    @Test
    void importCsv_validRows_importsSuccessfully() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer,topic
                Q001,Qual a taxa de IVA?,A taxa normal é 23%.,IVA
                """);

        ImportReport report = importService.importCsv(org.getId(), userId, "csv-test", csv, false, 0);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.invalid()).isZero();
        assertThat(report.totalRows()).isEqualTo(1);

        var qa = qaRepository.findAll().getFirst();
        assertThat(qa.getOriginalQuestion()).isEqualTo("Qual a taxa de IVA?");
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.getTopic()).isEqualTo(KnowledgeTopic.IVA);
    }

    // 2. JSON válido
    @Test
    void importJson_validRows_importsSuccessfully() throws Exception {
        InputStream json = stream("""
                [{"externalKey":"J001","question":"Qual o prazo IRC?","answer":"Até 31 de Maio.","topic":"IRC"}]
                """);

        ImportReport report = importService.importJson(org.getId(), userId, "json-test", json, false, 0);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // 3. UTF-8 com caracteres portugueses
    @Test
    void importCsv_utf8Portuguese_preservesCharacters() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer
                UTF001,Qual é a obrigação?,Sujeitos passivos com volume de negócios superior a 650.000 €.
                """);

        importService.importCsv(org.getId(), userId, "utf8-test", csv, false, 0);

        var qa = qaRepository.findAll().getFirst();
        assertThat(qa.getOriginalQuestion()).contains("obrigação");
        assertThat(qa.getOriginalAnswer()).contains("negócios");
        assertThat(qa.getOriginalAnswer()).contains("650.000 €");
    }

    // 4. Campos opcionais — importa sem topic/risk
    @Test
    void importCsv_optionalFieldsMissing_importWithDefaults() throws Exception {
        InputStream csv = csv("""
                question,answer
                Pergunta simples?,Resposta simples.
                """);

        ImportReport report = importService.importCsv(org.getId(), userId, "optional-test", csv, false, 0);

        assertThat(report.imported()).isEqualTo(1);
        var qa = qaRepository.findAll().getFirst();
        assertThat(qa.getTopic()).isNull();
        assertThat(qa.getJurisdiction()).isEqualTo("PT");
    }

    // 5. Linha sem question → inválida
    @Test
    void importCsv_missingQuestion_marksRowInvalid() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer
                BAD001,,Resposta sem pergunta.
                """);

        ImportReport report = importService.importCsv(org.getId(), userId, "invalid-test", csv, false, 0);

        assertThat(report.invalid()).isEqualTo(1);
        assertThat(report.imported()).isZero();
        assertThat(report.issues()).hasSize(1);
        assertThat(report.issues().getFirst().message()).containsIgnoringCase("question");
    }

    // 6. Mesmo externalKey em re-import → actualiza metadata, não duplica
    @Test
    void importCsv_sameExternalKey_updatesMetadataIdempotently() throws Exception {
        InputStream csv1 = csv("""
                externalKey,question,answer,notes
                DUP001,Pergunta duplicada?,Resposta original.,Nota inicial.
                """);
        importService.importCsv(org.getId(), userId, "src", csv1, false, 0);

        InputStream csv2 = csv("""
                externalKey,question,answer,notes
                DUP001,Pergunta duplicada?,Resposta original.,Nota actualizada.
                """);
        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv2, false, 0);

        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.imported()).isZero();
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // 7. Duplicate exacto (mesma pergunta + mesma resposta, sem externalKey) → skip
    @Test
    void importCsv_exactDuplicate_skipsWithoutCreating() throws Exception {
        String csvContent = """
                question,answer
                Mesma pergunta?,Mesma resposta.
                """;
        importService.importCsv(org.getId(), userId, "src", csv(csvContent), false, 0);
        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv(csvContent), false, 0);

        assertThat(report.duplicated()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // 8. Mesma pergunta, resposta diferente → importa e assinala conflito
    @Test
    void importCsv_sameQuestionDifferentAnswer_importsWithConflictFlag() throws Exception {
        importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQual o IVA?,Taxa é 23%."), false, 0);

        ImportReport report = importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQual o IVA?,A taxa normal é de 23%."), false, 0);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(2);
        assertThat(report.issues()).anyMatch(i ->
                i.message() != null && i.message().toLowerCase().contains("conflict"));
    }

    // 9. Dry-run — não persiste
    @Test
    void importCsv_dryRun_doesNotPersist() throws Exception {
        InputStream csv = csv("""
                question,answer
                Pergunta dry-run?,Resposta dry-run.
                """);

        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv, true, 0);

        assertThat(report.dryRun()).isTrue();
        assertThat(qaRepository.findAll()).isEmpty();
    }

    // 10. Limit — para após N importadas
    @Test
    void importCsv_withLimit_stopsAfterLimitReached() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer
                L001,Pergunta 1?,Resposta 1.
                L002,Pergunta 2?,Resposta 2.
                L003,Pergunta 3?,Resposta 3.
                """);

        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv, false, 1);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // -------------------------------------------------------------------------

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
