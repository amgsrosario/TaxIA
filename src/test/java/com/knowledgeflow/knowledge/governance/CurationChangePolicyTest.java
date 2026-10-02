package com.knowledgeflow.knowledge.governance;

import static com.knowledgeflow.knowledge.governance.CurationChangeKind.CONSERVATIVE;
import static com.knowledgeflow.knowledge.governance.CurationChangeKind.EXPANSIVE;
import static com.knowledgeflow.knowledge.governance.CurationChangeKind.FREE;
import static com.knowledgeflow.knowledge.governance.CurationChangeKind.MATERIAL;
import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Classificação central das alterações de curadoria (ADR-005). */
class CurationChangePolicyTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 1);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 31);

    private static CurationSnapshot base() {
        return new CurationSnapshot("Pergunta?", "Curta.", "Técnica.", KnowledgeTopic.IRS, "Categoria F", "PT",
                KnowledgeRiskLevel.MEDIUM, false, JAN, DEC, "Nota.");
    }

    private static CurationSnapshot with(String field, Object value) {
        CurationSnapshot b = base();
        return new CurationSnapshot(
                field.equals("normalizedQuestion") ? (String) value : b.normalizedQuestion(),
                field.equals("shortAnswer") ? (String) value : b.shortAnswer(),
                field.equals("technicalAnswer") ? (String) value : b.technicalAnswer(),
                field.equals("topic") ? (KnowledgeTopic) value : b.topic(),
                field.equals("subtopic") ? (String) value : b.subtopic(),
                field.equals("jurisdiction") ? (String) value : b.jurisdiction(),
                field.equals("riskLevel") ? (KnowledgeRiskLevel) value : b.riskLevel(),
                field.equals("requiresHumanValidation") ? (Boolean) value : b.requiresHumanValidation(),
                field.equals("validFrom") ? (LocalDate) value : b.validFrom(),
                field.equals("validTo") ? (LocalDate) value : b.validTo(),
                field.equals("notes") ? (String) value : b.notes());
    }

    private static CurationChangeKind kind(String field, Object value) {
        CurationChanges changes = CurationChangePolicy.classify(base(), with(field, value));
        assertThat(changes.fields()).containsOnlyKeys(field);
        return changes.fields().get(field);
    }

    @Test
    void noChange_isEmpty_andBlankEqualsNull() {
        assertThat(CurationChangePolicy.classify(base(), base()).isEmpty()).isTrue();
        CurationSnapshot nullNotes = with("notes", null);
        assertThat(CurationChangePolicy.classify(nullNotes, with("notes", "  ")).isEmpty()).isTrue();
        assertThat(CurationChangePolicy.classify(base(), with("technicalAnswer", "  Técnica. ")).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"normalizedQuestion", "shortAnswer", "technicalAnswer", "subtopic", "jurisdiction"})
    void contentFields_areMaterial(String field) {
        assertThat(kind(field, "Outro valor")).isEqualTo(MATERIAL);
        assertThat(kind(field, null)).isEqualTo(MATERIAL);
    }

    @Test
    void topic_isMaterial() {
        assertThat(kind("topic", KnowledgeTopic.IVA)).isEqualTo(MATERIAL);
        assertThat(kind("topic", null)).isEqualTo(MATERIAL);
    }

    @Test
    void risk_upIsConservative_downIsExpansive() {
        assertThat(kind("riskLevel", KnowledgeRiskLevel.HIGH)).isEqualTo(CONSERVATIVE);
        assertThat(kind("riskLevel", KnowledgeRiskLevel.CRITICAL)).isEqualTo(CONSERVATIVE);
        assertThat(kind("riskLevel", KnowledgeRiskLevel.LOW)).isEqualTo(EXPANSIVE);
    }

    @Test
    void humanValidation_onIsConservative_offIsExpansive() {
        assertThat(kind("requiresHumanValidation", true)).isEqualTo(CONSERVATIVE);
        CurationSnapshot required = with("requiresHumanValidation", true);
        assertThat(CurationChangePolicy.classify(required, base()).fields().get("requiresHumanValidation"))
                .isEqualTo(EXPANSIVE);
    }

    @Test
    void validFrom_laterRestricts_earlierOrUnboundedExpands() {
        assertThat(kind("validFrom", JUN)).isEqualTo(CONSERVATIVE);
        assertThat(kind("validFrom", JAN.minusDays(1))).isEqualTo(EXPANSIVE);
        assertThat(kind("validFrom", null)).isEqualTo(EXPANSIVE);
        // sem início → passar a ter início restringe
        CurationSnapshot open = with("validFrom", null);
        assertThat(CurationChangePolicy.classify(open, base()).fields().get("validFrom")).isEqualTo(CONSERVATIVE);
    }

    @Test
    void validTo_earlierRestricts_laterOrUnboundedExpands() {
        assertThat(kind("validTo", JUN)).isEqualTo(CONSERVATIVE);
        assertThat(kind("validTo", DEC.plusDays(1))).isEqualTo(EXPANSIVE);
        assertThat(kind("validTo", null)).isEqualTo(EXPANSIVE);
        CurationSnapshot open = with("validTo", null);
        assertThat(CurationChangePolicy.classify(open, base()).fields().get("validTo")).isEqualTo(CONSERVATIVE);
    }

    @Test
    void notes_areFree_andDoNotRequireRevalidation() {
        assertThat(kind("notes", "Outra nota.")).isEqualTo(FREE);
        assertThat(CurationChangePolicy.classify(base(), with("notes", "x")).requiresRevalidation()).isFalse();
    }

    @Test
    void mixedChanges_requireRevalidation_andListOnlyThoseFields() {
        CurationSnapshot requested = new CurationSnapshot("Pergunta?", "Curta.", "Técnica nova.", KnowledgeTopic.IRS,
                "Categoria F", "PT", KnowledgeRiskLevel.HIGH, false, JAN, DEC, "Nota nova.");
        CurationChanges changes = CurationChangePolicy.classify(base(), requested);
        assertThat(changes.fieldNames()).containsExactly("technicalAnswer", "riskLevel", "notes");
        assertThat(changes.requiresRevalidation()).isTrue();
        assertThat(changes.revalidationFields()).containsExactly("technicalAnswer");
    }
}
