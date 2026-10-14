package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * C8 / ADR-008: actualidade governada da fonte, calculada com relógio fixo. Hoje = 2026-10-13
 * (Europe/Lisbon). Os dias contam desde o dia civil da verificação; limites inclusivos.
 */
class SourceFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final SourceFreshnessPolicy POLICY = new SourceFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private static OffsetDateTime daysAgo(long days) {
        return TODAY.minusDays(days).atTime(8, 0).atOffset(ZoneOffset.ofHours(1));
    }

    private static SourceFreshnessPolicy.Facts facts(KnowledgeSourceType type, OffsetDateTime verifiedAt,
            boolean consolidated, boolean stable, String reason, LocalDate validTo, String title) {
        return new SourceFreshnessPolicy.Facts(type, title, null, validTo, verifiedAt, verifiedAt != null,
                consolidated, stable, reason, stable);
    }

    private static FreshnessStatus status(KnowledgeSourceType type, long daysAgo, boolean stable) {
        return POLICY.assess(facts(type, daysAgo(daysAgo), true, stable, stable ? "Regra estável há décadas." : null,
                null, "Fonte")).status();
    }

    // ------------------------------------------------------------ CURRENT

    @ParameterizedTest(name = "legislação verificada há {0} dias → {1}")
    @CsvSource({"0, CURRENT", "89, CURRENT", "90, CURRENT", "91, UNCERTAIN"})
    void legislationCurrentWindow(long days, FreshnessStatus expected) {
        assertThat(status(KnowledgeSourceType.LEGISLATION, days, false)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "FAQ/orientação verificada há {0} dias → {1}")
    @CsvSource({"0, CURRENT", "59, CURRENT", "60, CURRENT", "61, UNCERTAIN"})
    void officialFaqCurrentWindow(long days, FreshnessStatus expected) {
        assertThat(status(KnowledgeSourceType.OFFICIAL_FAQ, days, false)).isEqualTo(expected);
        assertThat(status(KnowledgeSourceType.ADMINISTRATIVE_GUIDANCE, days, false)).isEqualTo(expected);
    }

    @Test
    @DisplayName("CURRENT indica até quando; legislação sem versão consolidada confirmada nunca é CURRENT")
    void currentUntil_andConsolidatedRequirement() {
        SourceFreshnessPolicy.Assessment a = POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(10), true,
                false, null, null, "Lei"));
        assertThat(a.currentUntil()).isEqualTo(TODAY.minusDays(10).plusDays(90));
        assertThat(a.revalidationNeeded()).isFalse();

        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(0), false, false, null, null, "Lei"))
                .status()).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    // ------------------------------------------------------------ STABLE_BUT_OLD

    @ParameterizedTest(name = "legislação estável há {0} dias → {1}")
    @CsvSource({"91, STABLE_BUT_OLD", "200, STABLE_BUT_OLD", "365, STABLE_BUT_OLD", "366, UNCERTAIN"})
    void legislationStableLimit(long days, FreshnessStatus expected) {
        assertThat(status(KnowledgeSourceType.LEGISLATION, days, true)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "FAQ estável há {0} dias → {1}")
    @CsvSource({"61, STABLE_BUT_OLD", "180, STABLE_BUT_OLD", "181, UNCERTAIN"})
    void faqStableLimit(long days, FreshnessStatus expected) {
        assertThat(status(KnowledgeSourceType.OFFICIAL_FAQ, days, true)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Matéria estável dentro da janela CURRENT continua CURRENT; sem justificação ou sem curador nunca é STABLE")
    void stableNeverAutomatic() {
        assertThat(status(KnowledgeSourceType.LEGISLATION, 30, true)).isEqualTo(FreshnessStatus.CURRENT);
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(120), true, true, "  ", null, "Lei"))
                .status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        SourceFreshnessPolicy.Facts noCurator = new SourceFreshnessPolicy.Facts(KnowledgeSourceType.LEGISLATION, "Lei",
                null, null, daysAgo(120), true, true, true, "Estável.", false);
        assertThat(POLICY.assess(noCurator).status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(status(KnowledgeSourceType.LEGISLATION, 120, false)).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    // ------------------------------------------------------------ OUTDATED

    @ParameterizedTest(name = "validTo = hoje{0} → {1}")
    @CsvSource({"0, CURRENT", "-1, OUTDATED", "1, CURRENT"})
    void validToBoundary(long offsetDays, FreshnessStatus expected) {
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(5), true, false, null,
                TODAY.plusDays(offsetDays), "Lei")).status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("validTo expirado → OUTDATED mesmo sem verificação; marcador textual → OUTDATED mesmo verificada")
    void outdatedSignals() {
        assertThat(POLICY.assess(facts(KnowledgeSourceType.OTHER, null, false, false, null, TODAY.minusDays(1), "X"))
                .status()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(1), true, false, null, null,
                "Artigo 123.º, n.º 4 (revogado)")).status()).isEqualTo(FreshnessStatus.OUTDATED);
    }

    @Test
    @DisplayName("Fonte antiga não é OUTDATED só por ser antiga")
    void ageAloneIsNeverOutdated() {
        assertThat(status(KnowledgeSourceType.LEGISLATION, 3000, false)).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    // ------------------------------------------------------------ UNCERTAIN (fail-safe)

    @Test
    @DisplayName("Sem verifiedAt, sem verificador, data futura ou tipo não elegível → UNCERTAIN")
    void uncertainByDefault() {
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, null, false, false, null, null, "Lei")).status())
                .isEqualTo(FreshnessStatus.UNCERTAIN);
        SourceFreshnessPolicy.Facts noVerifier = new SourceFreshnessPolicy.Facts(KnowledgeSourceType.OFFICIAL_FAQ, "FAQ",
                null, null, daysAgo(1), false, false, false, null, false);
        assertThat(POLICY.assess(noVerifier).status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(POLICY.assess(facts(KnowledgeSourceType.OFFICIAL_FAQ, daysAgo(-2), false, false, null, null, "FAQ"))
                .status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        for (KnowledgeSourceType t : new KnowledgeSourceType[] {KnowledgeSourceType.CASE_LAW,
                KnowledgeSourceType.INTERNAL_OPINION, KnowledgeSourceType.ACCOUNTING_STANDARD, KnowledgeSourceType.OTHER}) {
            assertThat(POLICY.assess(facts(t, daysAgo(0), false, false, null, null, "Outra")).status())
                    .as(t.name()).isEqualTo(FreshnessStatus.UNCERTAIN);
        }
        assertThat(POLICY.assess(facts(null, daysAgo(0), true, false, null, null, "?")).status())
                .isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    // ------------------------------------------------------------ revisão independente

    @Test
    @DisplayName("Revisão #2: marcador acentuado \"substituída\" → OUTDATED (comparação sem acentos)")
    void accentedMarker_isOutdated() {
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, daysAgo(1), true, false, null, null,
                "Lei 1/2000 (substituída)")).status()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(SourceFreshnessPolicy.hasOutdatedMarker("Norma DESACTUALIZADA", null)).isTrue();
    }

    @Test
    @DisplayName("Revisão #4: verificação com instante futuro no mesmo dia → UNCERTAIN")
    void futureInstantSameDay_isUncertain() {
        OffsetDateTime laterToday = OffsetDateTime.parse("2026-10-13T20:00:00Z");
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, laterToday, true, false, null, null, "Lei"))
                .status()).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    @Test
    @DisplayName("Revisão #5: fonte ainda não em vigor (validFrom futuro) → UNCERTAIN mesmo verificada")
    void futureValidFrom_isUncertain() {
        SourceFreshnessPolicy.Facts notYet = new SourceFreshnessPolicy.Facts(KnowledgeSourceType.LEGISLATION, "Lei",
                null, null, daysAgo(1), true, true, false, null, false, TODAY.plusDays(1));
        assertThat(POLICY.assess(notYet).status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        SourceFreshnessPolicy.Facts inForce = new SourceFreshnessPolicy.Facts(KnowledgeSourceType.LEGISLATION, "Lei",
                null, null, daysAgo(1), true, true, false, null, false, TODAY);
        assertThat(POLICY.assess(inForce).status()).isEqualTo(FreshnessStatus.CURRENT);
    }

    // ------------------------------------------------------------ timezone

    @Test
    @DisplayName("Fronteira do dia em Europe/Lisbon: verificação às 23:30 de Lisboa conta como esse dia")
    void lisbonCalendarDay() {
        // Hoje 2026-10-13 em Lisboa; verificação a 2026-07-14 às 23:30 de Lisboa (22:30 UTC) = 91 dias → UNCERTAIN.
        OffsetDateTime lateLisbon = OffsetDateTime.parse("2026-07-14T22:30:00Z");
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, lateLisbon, true, false, null, null, "Lei"))
                .status()).isEqualTo(FreshnessStatus.UNCERTAIN);
        // 2026-07-15 às 00:30 de Lisboa (23:30 UTC do dia 14) = 90 dias → CURRENT.
        OffsetDateTime earlyLisbon = OffsetDateTime.parse("2026-07-14T23:30:00Z");
        assertThat(POLICY.assess(facts(KnowledgeSourceType.LEGISLATION, earlyLisbon, true, false, null, null, "Lei"))
                .status()).isEqualTo(FreshnessStatus.CURRENT);
        // "Hoje" é a data civil de Lisboa, não a UTC: 23:30 UTC de 13/10 já é 00:30 de 14/10 em Lisboa (UTC+1).
        SourceFreshnessPolicy lateNight = new SourceFreshnessPolicy(Clock.fixed(Instant.parse("2026-10-13T23:30:00Z"), ZoneOffset.UTC));
        assertThat(lateNight.today()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    @DisplayName("Severidade de agregação: OUTDATED > UNCERTAIN > STABLE_BUT_OLD > CURRENT; ausente = UNCERTAIN")
    void severityOrder() {
        assertThat(SourceFreshnessPolicy.severity(FreshnessStatus.OUTDATED))
                .isGreaterThan(SourceFreshnessPolicy.severity(FreshnessStatus.UNCERTAIN));
        assertThat(SourceFreshnessPolicy.severity(FreshnessStatus.UNCERTAIN))
                .isGreaterThan(SourceFreshnessPolicy.severity(FreshnessStatus.STABLE_BUT_OLD));
        assertThat(SourceFreshnessPolicy.severity(FreshnessStatus.STABLE_BUT_OLD))
                .isGreaterThan(SourceFreshnessPolicy.severity(FreshnessStatus.CURRENT));
        assertThat(SourceFreshnessPolicy.severity(null)).isEqualTo(SourceFreshnessPolicy.severity(FreshnessStatus.UNCERTAIN));
    }
}
