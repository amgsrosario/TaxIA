package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSource;
import org.junit.jupiter.api.Test;

/**
 * Testes das heurísticas simples e conservadoras do {@link SourceAssessmentService} (D5).
 *
 * <p>Só se testam decisões determinísticas a partir dos campos reais de
 * {@link AnswerSource} ({@code title}/{@code reference}). Sem scoring, thresholds nem web.
 */
class SourceAssessmentServiceTest {

    private final SourceAssessmentService service = new SourceAssessmentService();

    private AnswerSource source(String title, String reference) {
        return new AnswerSource(title, reference, 0.8);
    }

    // --- authorityLevel ---

    @Test
    void legislation_isLegal_andStrongQuality() {
        var src = source("Código do IVA — artigo 18.º", "CIVA art. 18.º");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.LEGAL);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.STRONG);
    }

    @Test
    void officialFaq_isOfficialFaq_andStrongQuality() {
        var src = source("FAQ oficial — Portal das Finanças", "Perguntas frequentes IVA");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.OFFICIAL_FAQ);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.STRONG);
    }

    @Test
    void administrativeGuidance_isOfficialAdministrative_andStrongQuality() {
        var src = source("Ofício circulado n.º 30254", "Instrução da AT");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.OFFICIAL_ADMINISTRATIVE);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.STRONG);
    }

    @Test
    void caseLaw_isJurisprudence_andAdequateQuality() {
        var src = source("Acórdão do STA de 2019", "Processo 01234/18");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.JURISPRUDENCE);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.ADEQUATE);
    }

    @Test
    void externalUrlNonOfficial_isExternalNonOfficial_andWeakQuality() {
        var src = source("Artigo de blogue fiscal", "https://exemplo-privado.pt/artigo");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.EXTERNAL_NON_OFFICIAL);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.WEAK);
    }

    @Test
    void noSignals_isInternalCurated_withReference_isAdequate() {
        var src = source("Parecer interno sobre enquadramento", "Ref. interna 2024/07");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.INTERNAL_CURATED);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.ADEQUATE);
    }

    @Test
    void noSignals_isInternalCurated_withoutReference_isLimited() {
        var src = source("Nota genérica", "");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.INTERNAL_CURATED);
        assertThat(service.assessSourceQuality(src)).isEqualTo(SourceQuality.LIMITED);
    }

    @Test
    void blankSource_defaultsToInternalCurated_withoutThrowing() {
        var src = source("", "");

        assertThat(service.assessAuthorityLevel(src)).isEqualTo(AuthorityLevel.INTERNAL_CURATED);
    }

    // --- sourceRole ---

    @Test
    void role_defaultsToPrimary_withoutDerivationEvidence() {
        assertThat(service.assessSourceRole(source("Código do IRC", "CIRC art. 23.º")))
                .isEqualTo(SourceRole.PRIMARY);
    }

    @Test
    void role_isDerivativeReplicated_onlyWithEvidence() {
        var src = source("Resumo de artigo", "Adaptado de outra fonte");

        assertThat(service.assessSourceRole(src)).isEqualTo(SourceRole.DERIVATIVE_REPLICATED);
    }

    // --- sourceDiversity ---

    @Test
    void diversity_isMaterial_whenOwnCorePresent() {
        assertThat(service.assessSourceDiversity(source("CIVA", "CIVA art. 9.º")))
                .isEqualTo(SourceDiversity.MATERIAL_DIVERSITY);
    }

    @Test
    void diversity_isMixedOrUnclear_whenNoReference() {
        assertThat(service.assessSourceDiversity(source("Nota", "")))
                .isEqualTo(SourceDiversity.MIXED_OR_UNCLEAR);
    }

    @Test
    void diversity_isSameCore_withDerivationEvidence() {
        assertThat(service.assessSourceDiversity(source("Cópia", "Reprodução de fonte oficial")))
                .isEqualTo(SourceDiversity.SAME_CORE);
    }

    // --- freshnessStatus ---

    @Test
    void freshness_defaultsToUncertain() {
        assertThat(service.assessFreshnessStatus(source("CIVA art. 18.º", "CIVA")))
                .isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    @Test
    void freshness_isOutdated_onlyWithExplicitRevocationMarker() {
        assertThat(service.assessFreshnessStatus(source("Ofício revogado", "Sem efeito desde 2020")))
                .isEqualTo(FreshnessStatus.OUTDATED);
    }

    @Test
    void freshness_notOutdated_byAbsenceOfData() {
        assertThat(service.assessFreshnessStatus(source("Documento antigo de 1998", "")))
                .isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    // --- sourceCore ---

    @Test
    void core_usesLegalReferenceBeforeTitle() {
        var src = source("Título descritivo longo", "CIVA art. 18.º");

        assertThat(service.buildSourceCore(src)).isEqualTo("civa art. 18.º");
    }

    @Test
    void core_fallsBackToTitle_whenReferenceBlank() {
        var src = source("Regime  Geral   do IVA", "");

        // Normalização mínima: colapsa espaços e passa a minúsculas.
        assertThat(service.buildSourceCore(src)).isEqualTo("regime geral do iva");
    }

    @Test
    void core_isNull_whenNothingUsable() {
        assertThat(service.buildSourceCore(source("", ""))).isNull();
    }

    @Test
    void diversityGroup_equalsCore_inThisPhase() {
        var src = source("Título", "CIRS art. 10.º");

        assertThat(service.buildSourceDiversityGroup(src)).isEqualTo(service.buildSourceCore(src));
    }
}
