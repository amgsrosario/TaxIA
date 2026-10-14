package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Revisão #1 (C8, ADR-008): a mesma fonte citada por duas Q&amp;A usadas aparece uma vez, mas a
 * deduplicação nunca esconde a cópia pior — fica a de pior estado de actualidade calculado.
 */
class CuratedSourceFreshnessDedupTest {

    private static final UUID QA1 = UUID.randomUUID();
    private static final UUID QA2 = UUID.randomUUID();
    private static final String URL = "https://dre.pt/civa";
    private static final OffsetDateTime VERIFIED = OffsetDateTime.ofInstant(FreshnessFixtures.NOW, ZoneOffset.UTC);

    private static KnowledgeSourceReferenceRow row(UUID qa, OffsetDateTime verifiedAt, LocalDate validTo) {
        return new KnowledgeSourceReferenceRow(qa, UUID.randomUUID(), KnowledgeSourceType.LEGISLATION, "CIVA art. 36.º",
                "CIVA, art. 36.º", URL, OffsetDateTime.parse("2026-01-01T00:00:00Z"), validTo, verifiedAt,
                verifiedAt != null ? UUID.randomUUID() : null, verifiedAt != null, false, null, null, null);
    }

    private static List<ResolvedAnswerSource> resolve(KnowledgeSourceReferenceRow r1, KnowledgeSourceReferenceRow r2) {
        KnowledgeSourceReferenceRepository repo = mock(KnowledgeSourceReferenceRepository.class);
        when(repo.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(r1, r2));
        return new CuratedSourceResolver(repo, FreshnessFixtures.POLICY).resolve(List.of(
                new AnswerSource("Q1", "Q1", 0.95, QA1), new AnswerSource("Q2", "Q2", 0.90, QA2)));
    }

    private static FreshnessStatus status(ResolvedAnswerSource r) {
        return FreshnessFixtures.POLICY.assess(r.curated().freshnessFacts()).status();
    }

    @Test
    @DisplayName("Cópia verificada (Q&A1) + cópia com validTo expirado (Q&A2) → fica a OUTDATED")
    void expiredCopyWins() {
        List<ResolvedAnswerSource> resolved = resolve(row(QA1, VERIFIED, null),
                row(QA2, null, LocalDate.of(2026, 1, 1)));
        assertThat(resolved).hasSize(1);
        assertThat(status(resolved.get(0))).isEqualTo(FreshnessStatus.OUTDATED);
    }

    @Test
    @DisplayName("Cópia verificada + cópia não verificada → fica a UNCERTAIN; duas verificadas → CURRENT")
    void unverifiedCopyWins_andEqualCopiesStay() {
        assertThat(status(resolve(row(QA1, VERIFIED, null), row(QA2, null, null)).get(0)))
                .isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(status(resolve(row(QA1, null, null), row(QA2, VERIFIED, null)).get(0)))
                .isEqualTo(FreshnessStatus.UNCERTAIN);
        List<ResolvedAnswerSource> both = resolve(row(QA1, VERIFIED, null), row(QA2, VERIFIED, null));
        assertThat(both).hasSize(1);
        assertThat(status(both.get(0))).isEqualTo(FreshnessStatus.CURRENT);
    }

    @Test
    @DisplayName("Através do mapper: a resposta não sai CURRENT/limpa quando a outra cópia está expirada")
    void endToEnd_noFalseCurrent() {
        List<ResolvedAnswerSource> resolved = resolve(row(QA1, VERIFIED, null), row(QA2, null, LocalDate.of(2026, 1, 1)));
        var grounded = new com.knowledgeflow.ai.grounding.GroundedAIResponse("R.",
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, "OK",
                List.of(new AnswerSource("Q1", "Q1", 0.95, QA1), new AnswerSource("Q2", "Q2", 0.90, QA2)),
                List.of(), List.of(), false, null, true, false, null, 0, "stub", "stub", 0, 0, 0L, List.of());
        DocumentedTaxiaAnswer answer = FreshnessFixtures.mapper().fromGroundedResponse("P?", grounded, resolved,
                FoundationGovernance.notAssessed());
        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
    }
}
