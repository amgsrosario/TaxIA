package com.knowledgeflow.research.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.EvidenceClassification;
import com.knowledgeflow.research.enums.InvestigationStatus;
import com.knowledgeflow.research.enums.InvestigationTrigger;
import com.knowledgeflow.research.enums.ValidityStatus;
import com.knowledgeflow.research.projection.ResearchAnswerProjection.AnswerKind;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Tabela de nulabilidade por estado da projecção (especificação KF-CONV-01 v13, §3.6). */
class ResearchAnswerProjectorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID ORG = UUID.randomUUID();
    private static final String SECRET_QUESTION = "Pergunta com dados do cliente 123456789";
    private static final String TECHNICAL_FAILURE = "Falha técnica: NullPointerException";
    private static final String ARTICLE_TEXT = "Texto integral do artigo 78.º do CIRS (não deve aparecer na projecção)";

    private static ResearchInvestigation open() {
        return new ResearchInvestigation(ORG, UUID.randomUUID(), SECRET_QUESTION, InvestigationTrigger.NO_RELEVANT_QA, NOW);
    }

    private static ResearchEvidence evidence(ResearchInvestigation i, EvidenceClassification c, ValidityStatus v) {
        return new ResearchEvidence(ORG, i.getId(), UUID.randomUUID(), KnowledgeSourceType.values()[0], "AT",
                "CIRS:78", "Artigo 78.º do CIRS", "Deduções à colecta",
                "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs78.aspx",
                NOW, "a".repeat(64), ARTICLE_TEXT, null, v, "razão de vigência", c, "razão " + c, NOW);
    }

    private static void assertEmptyAnswer(ResearchAnswerProjection p) {
        assertThat(p.answerType()).isNull();
        assertThat(p.parecerRequirement()).isNull();
        assertThat(p.answerKind()).isNull();
        assertThat(p.answer()).isNull();
        assertThat(p.limitations()).isEmpty();
        assertThat(p.assumptions()).isEmpty();
        assertThat(p.decisionReasons()).isEmpty();
        assertThat(p.evidence()).isEmpty();
    }

    @Test
    void openHasOnlyIdentityAndStatus() {
        ResearchInvestigation i = open();
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i,
                List.of(evidence(i, EvidenceClassification.ACCEPTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL)),
                List.of("Título"));
        assertThat(p.investigationId()).isEqualTo(i.getId());
        assertThat(p.status()).isEqualTo(InvestigationStatus.OPEN);
        assertThat(p.notStartedReason()).isNull();
        assertThat(p.coverageTitles()).isEmpty();
        assertThat(p.failure()).isNull();
        assertEmptyAnswer(p);
    }

    @Test
    void failedExposesOnlyGenericMessageNeverTheTechnicalReason() {
        ResearchInvestigation i = open();
        i.fail(TECHNICAL_FAILURE, NOW);
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i,
                List.of(evidence(i, EvidenceClassification.ACCEPTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL)), null);
        assertThat(p.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(p.failure()).isEqualTo(ResearchAnswerProjector.GENERIC_FAILURE).doesNotContain("NullPointer");
        assertThat(p.notStartedReason()).isNull();
        assertThat(p.coverageTitles()).isEmpty();
        assertEmptyAnswer(p);
    }

    @Test
    void notStartedWithRelevantContentCarriesCleanCoverageTitles() {
        ResearchInvestigation i = new ResearchInvestigation(ORG, UUID.randomUUID(), SECRET_QUESTION,
                InvestigationTrigger.RELEVANT_QA_EXISTS, NOW);
        List<String> titles = new ArrayList<>(List.of(" Q&A sobre deduções ", "Documento validado", "", "Documento validado"));
        titles.add(null);
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i, List.of(), titles);
        assertThat(p.status()).isEqualTo(InvestigationStatus.NOT_STARTED);
        assertThat(p.notStartedReason()).isEqualTo(InvestigationTrigger.RELEVANT_QA_EXISTS);
        assertThat(p.coverageTitles()).containsExactly("Q&A sobre deduções", "Documento validado");
        assertThat(p.failure()).isNull();
        assertEmptyAnswer(p);
    }

    @Test
    void notStartedBecauseCorpusUnavailableHasNoTitles() {
        // Estado proposto para o modo integrado (fail closed); hoje o serviço prossegue nesse caso.
        ResearchInvestigation i = open();
        ReflectionTestUtils.setField(i, "triggerReason", InvestigationTrigger.CORPUS_CHECK_UNAVAILABLE);
        ReflectionTestUtils.setField(i, "status", InvestigationStatus.NOT_STARTED);
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i, List.of(), List.of("Não deve aparecer"));
        assertThat(p.notStartedReason()).isEqualTo(InvestigationTrigger.CORPUS_CHECK_UNAVAILABLE);
        assertThat(p.coverageTitles()).isEmpty();
        assertEmptyAnswer(p);
    }

    @Test
    void notStartedWithInadmissibleCauseFailsClosed() {
        ResearchInvestigation i = open();
        ReflectionTestUtils.setField(i, "status", InvestigationStatus.NOT_STARTED);
        assertThatThrownBy(() -> ResearchAnswerProjector.project(i, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedDocumentedAnswerIsFundamentadaWithAllEvidenceClassified() {
        ResearchInvestigation i = open();
        i.complete(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES, ParecerRequirement.SUGGESTED, "Resposta composta",
                "Razão 1\n\nRazão 2", "Limitação A\n", "Pressuposto X", NOW);
        List<ResearchEvidence> ev = List.of(
                evidence(i, EvidenceClassification.ACCEPTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL),
                evidence(i, EvidenceClassification.REJECTED, ValidityStatus.REVOKED),
                evidence(i, EvidenceClassification.CONTRADICTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL),
                evidence(i, EvidenceClassification.INSUFFICIENT, ValidityStatus.UNCERTAIN));
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i, ev, List.of("ignorado"));
        assertThat(p.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(p.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(p.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(p.answerKind()).isEqualTo(AnswerKind.FUNDAMENTADA);
        assertThat(p.answer()).isEqualTo("Resposta composta");
        assertThat(p.decisionReasons()).containsExactly("Razão 1", "Razão 2");
        assertThat(p.limitations()).containsExactly("Limitação A");
        assertThat(p.assumptions()).containsExactly("Pressuposto X");
        assertThat(p.notStartedReason()).isNull();
        assertThat(p.coverageTitles()).isEmpty();
        assertThat(p.failure()).isNull();
        assertThat(p.evidence()).extracting(ResearchAnswerProjection.EvidenceSummary::classification)
                .containsExactly(EvidenceClassification.ACCEPTED, EvidenceClassification.REJECTED,
                        EvidenceClassification.CONTRADICTED, EvidenceClassification.INSUFFICIENT);
        assertThat(p.evidence()).allSatisfy(s -> {
            assertThat(s.notice()).isEqualTo(ResearchAnswerProjector.NOT_PROMOTED_NOTICE);
            assertThat(s.contentSha256()).hasSize(64);
            assertThat(s.classificationReason()).startsWith("razão ");
        });
        // A evidência revogada aparece sempre como REJECTED (classificação do serviço) e com a vigência REVOKED.
        assertThat(p.evidence().get(1).validityStatus()).isEqualTo(ValidityStatus.REVOKED);
    }

    @Test
    void completedLimitAnswerIsTextoLimite() {
        ResearchInvestigation i = open();
        i.complete(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED, "Resposta-limite", null, null, null, NOW);
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i, null, null);
        assertThat(p.answerKind()).isEqualTo(AnswerKind.TEXTO_LIMITE);
        assertThat(p.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        assertThat(p.limitations()).isEmpty();
        assertThat(p.evidence()).isEmpty();
    }

    @Test
    void completedWithInadmissibleTypeOrMissingParecerFailsClosed() {
        ResearchInvestigation a = open();
        a.complete(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.SUGGESTED, "x", null, null, null, NOW);
        ReflectionTestUtils.setField(a, "answerType", AnswerType.CONSULTA_DOCUMENTADA);
        assertThatThrownBy(() -> ResearchAnswerProjector.project(a, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);

        ResearchInvestigation b = open();
        b.complete(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.SUGGESTED, "x", null, null, null, NOW);
        ReflectionTestUtils.setField(b, "parecerRequirement", null);
        assertThatThrownBy(() -> ResearchAnswerProjector.project(b, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evidenceFromAnotherInvestigationIsRefused() {
        ResearchInvestigation i = open();
        i.complete(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.SUGGESTED, "x", null, null, null, NOW);
        ResearchEvidence foreign = evidence(open(), EvidenceClassification.ACCEPTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL);
        assertThatThrownBy(() -> ResearchAnswerProjector.project(i, List.of(foreign), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectionIsImmutable() {
        ResearchInvestigation i = open();
        i.complete(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.SUGGESTED, "x", null, "L", null, NOW);
        ResearchAnswerProjection p = ResearchAnswerProjector.project(i, List.of(), List.of());
        assertThatThrownBy(() -> p.limitations().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    /** O corpo JSON só tem os campos da projecção: nada do rasto integral, nem a pergunta, o texto ou a causa técnica. */
    @Test
    void serialisedBodyHasOnlyProjectionFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ResearchInvestigation i = open();
        i.complete(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES, ParecerRequirement.SUGGESTED, "Resposta", "R",
                "L", "P", NOW);
        String json = mapper.writeValueAsString(ResearchAnswerProjector.project(i,
                List.of(evidence(i, EvidenceClassification.ACCEPTED, ValidityStatus.CONSOLIDATED_AT_RETRIEVAL)), null));
        JsonNode root = mapper.readTree(json);
        assertThat(names(root)).containsExactlyInAnyOrder("investigationId", "status", "notStartedReason",
                "coverageTitles", "answerType", "parecerRequirement", "answerKind", "answer", "limitations",
                "assumptions", "decisionReasons", "evidence", "failure");
        assertThat(names(root.get("evidence").get(0))).containsExactlyInAnyOrder("legalReference", "authority", "url",
                "retrievedAt", "contentSha256", "validityStatus", "classification", "classificationReason", "notice");
        assertThat(json).doesNotContain(SECRET_QUESTION, ARTICLE_TEXT);

        ResearchInvestigation f = open();
        f.fail(TECHNICAL_FAILURE, NOW);
        assertThat(mapper.writeValueAsString(ResearchAnswerProjector.project(f, null, null)))
                .doesNotContain(TECHNICAL_FAILURE, SECRET_QUESTION);
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) out.add(it.next());
        return out;
    }
}
