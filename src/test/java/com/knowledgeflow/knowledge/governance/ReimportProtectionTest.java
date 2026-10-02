package com.knowledgeflow.knowledge.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.knowledge.dto.ImportIssue;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-import com a mesma externalKey (ADR-005): nunca altera em silêncio uma Q&amp;A VALIDATED ou
 * publicada — salta o item, reporta e audita; os restantes itens continuam a ser importados.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ReimportProtectionTest {

    private static final String SRC = "reimport-test";

    @Autowired private KnowledgeQuestionAnswerImportService importService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AuditEventRepository auditRepository;

    private Organization org;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Reimport", null));
        userId = null;
    }

    @Test
    @DisplayName("VALIDATED, publicada e PENDING com a mesma chave: protegidas saltam; a não validada é actualizada")
    void reimport_protectsValidatedAndPublished() throws Exception {
        KnowledgeQuestionAnswer validated = entry("RI-VAL", true, false);
        KnowledgeQuestionAnswer published = entry("RI-PUB", true, true);
        KnowledgeQuestionAnswer pending = entry("RI-PEN", false, false);

        String csv = """
                externalKey,question,answer,topic,subtopic
                RI-VAL,Pergunta RI-VAL?,Resposta.,IVA,Outro subtema
                RI-PUB,Pergunta RI-PUB?,Resposta.,IVA,Outro subtema
                RI-PEN,Pergunta RI-PEN?,Resposta.,IVA,Outro subtema
                RI-NEW,Pergunta nova?,Resposta nova.,IVA,Novo
                """;
        ImportReport report = importService.importCsv(org.getId(), userId, SRC,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false, 0);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.issues()).filteredOn(i -> i.type() == ImportIssue.IssueType.PROTECTED_SKIPPED)
                .extracting(ImportIssue::externalKey).containsExactlyInAnyOrder("RI-VAL", "RI-PUB");

        for (KnowledgeQuestionAnswer protectedQa : new KnowledgeQuestionAnswer[] {validated, published}) {
            KnowledgeQuestionAnswer after = qaRepository.findById(protectedQa.getId()).orElseThrow();
            assertThat(after.getTopic()).isEqualTo(KnowledgeTopic.IRS);
            assertThat(after.getSubtopic()).isEqualTo("Categoria F");
            // requiresHumanValidation ausente no CSV nunca passa true → false em silêncio
            assertThat(after.isRequiresHumanValidation()).isTrue();
            assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                            org.getId(), "KnowledgeQuestionAnswer", protectedQa.getId()))
                    .anySatisfy(e -> {
                        assertThat(e.getAction()).isEqualTo(AuditAction.KNOWLEDGE_QA_UPDATED);
                        assertThat(e.getMetadata()).contains("event=REIMPORT_SKIPPED", "topic", "subtopic");
                    });
        }
        assertThat(qaRepository.findById(published.getId()).orElseThrow().isPublished()).isTrue();

        KnowledgeQuestionAnswer pendingAfter = qaRepository.findById(pending.getId()).orElseThrow();
        assertThat(pendingAfter.getTopic()).isEqualTo(KnowledgeTopic.IVA);
        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        org.getId(), "KnowledgeQuestionAnswer", pending.getId()))
                .anySatisfy(e -> assertThat(e.getMetadata()).contains("event=REIMPORT", "changedFields="));
    }

    @Test
    @DisplayName("Dry-run sobre Q&A protegida: reporta PROTECTED_SKIPPED e não escreve nada")
    void dryRun_reportsProtected() throws Exception {
        KnowledgeQuestionAnswer validated = entry("RI-DRY", true, false);
        String csv = "externalKey,question,answer,topic\nRI-DRY,Pergunta RI-DRY?,Resposta.,IVA\n";
        ImportReport report = importService.importCsv(org.getId(), userId, SRC,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), true, 0);

        assertThat(report.issues()).extracting(ImportIssue::type).contains(ImportIssue.IssueType.PROTECTED_SKIPPED);
        assertThat(qaRepository.findById(validated.getId()).orElseThrow().getTopic()).isEqualTo(KnowledgeTopic.IRS);
    }

    @Test
    @DisplayName("Mesma chave sem alterações numa VALIDATED: nada a saltar, nada muda")
    void unchangedRow_onValidated_isNoop() throws Exception {
        KnowledgeQuestionAnswer validated = entry("RI-SAME", true, false);
        String csv = "externalKey,question,answer,topic,subtopic,requiresHumanValidation\n"
                + "RI-SAME,Pergunta RI-SAME?,Resposta.,IRS,Categoria F,true\n";
        ImportReport report = importService.importCsv(org.getId(), userId, SRC,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false, 0);

        assertThat(report.issues()).extracting(ImportIssue::type).doesNotContain(ImportIssue.IssueType.PROTECTED_SKIPPED);
        assertThat(qaRepository.findById(validated.getId()).orElseThrow().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test
    @DisplayName("Ficheiro sem colunas opcionais sobre VALIDATED e publicada: no-op, lote segue, nada despromovido")
    void reimport_minimalFile_isNoopForProtected_andBatchContinues() throws Exception {
        KnowledgeQuestionAnswer validated = entry("RI-REV", true, false);
        KnowledgeQuestionAnswer published = entry("RI-REP", true, true);
        String csv = """
                externalKey,question,answer
                RI-REV,Pergunta RI-REV?,Resposta.
                RI-REP,Pergunta RI-REP?,Resposta.
                RI-NEW2,Pergunta nova 2?,Resposta nova.
                """;
        ImportReport report = importService.importCsv(org.getId(), userId, SRC,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), false, 0);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.issues()).extracting(ImportIssue::type).doesNotContain(ImportIssue.IssueType.PROTECTED_SKIPPED);
        for (KnowledgeQuestionAnswer qa : new KnowledgeQuestionAnswer[] {validated, published}) {
            KnowledgeQuestionAnswer after = qaRepository.findById(qa.getId()).orElseThrow();
            assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(after.isRequiresHumanValidation()).isTrue();
            assertThat(after.getReviewedBy()).isEqualTo("Revisor");
        }
        assertThat(qaRepository.findById(published.getId()).orElseThrow().isPublished()).isTrue();
    }

    private KnowledgeQuestionAnswer entry(String key, boolean validate, boolean publish) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, "Pergunta " + key + "?", "Resposta.", SRC, key);
        qa.updateCuration("Pergunta curada " + key + "?", "Curta.", "Técnica.", KnowledgeTopic.IRS, "Categoria F",
                "PT", KnowledgeRiskLevel.MEDIUM, true, null, null, null);
        qa.markPendingReview();
        if (validate) qa.validate("Revisor");
        if (publish) qa.markPublished("Publicador");
        return qaRepository.saveAndFlush(qa);
    }
}
