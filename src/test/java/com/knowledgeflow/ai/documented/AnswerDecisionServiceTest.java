package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes da decisão mínima, determinística e conservadora {@link AnswerDecisionService} (tarefa D6).
 *
 * <p>Valida a forma/prudência decidida a partir de {@link com.knowledgeflow.ai.grounding.AnswerSupportStatus},
 * {@code requiresHumanValidation} e dos sinais já avaliados em cada {@link SourceEvidence}. Não
 * valida scoring/thresholds (proibidos) nem projecção por visibilidade (D7).
 */
class AnswerDecisionServiceTest {

    private final AnswerDecisionService service = new AnswerDecisionService();

    /** Constrói uma {@link SourceEvidence} controlando só os sinais relevantes para a decisão. */
    private SourceEvidence source(
            AuthorityLevel authority, SourceRole role, SourceQuality quality,
            SourceDiversity diversity, FreshnessStatus freshness) {
        return new SourceEvidence(
                null, "Fonte", null, authority, role, quality, "core", "group", diversity, freshness,
                null, null, true, true, false, false,
                role == SourceRole.DERIVATIVE_REPLICATED, List.of(), null, List.of());
    }

    private SourceEvidence legalStrong() {
        return source(AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.CURRENT);
    }

    private SourceEvidence weakExternal() {
        return source(AuthorityLevel.EXTERNAL_NON_OFFICIAL, SourceRole.PRIMARY, SourceQuality.WEAK,
                SourceDiversity.MIXED_OR_UNCLEAR, FreshnessStatus.CURRENT);
    }

    private AnswerDecision decide(
            com.knowledgeflow.ai.grounding.AnswerSupportStatus status,
            boolean requiresHumanValidation, List<SourceEvidence> sources) {
        return service.decide(status, requiresHumanValidation, sources, List.of(), null);
    }

    @Test
    void supported_withLegalStrongSource_isConsultaDocumentada_andParecerNone() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of(legalStrong()));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(decision.confidenceSummary()).isNotBlank();
        assertThat(decision.sourceSummary()).contains("oficial/legal");
    }

    @Test
    void supported_withoutSources_staysConsultaDocumentada_notDowngraded() {
        // A ausência de fontes não deve segundo-adivinhar um SUPPORTED do grounding.
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of());

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        // Caminho feliz: sem sugestão de reformulação (isso é para Resposta-limite).
        assertThat(decision.nextSteps())
                .noneMatch(s -> s.contains("Reformular"));
    }

    @Test
    void supported_withOnlyWeakSources_becomesComLimitacoes_andParecerSuggested() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of(weakExternal()));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(decision.limitations()).isNotEmpty();
        assertThat(decision.warnings()).isNotEmpty();
    }

    @Test
    void supported_withOutdatedSource_emitsWarning_andEscalatesParecer() {
        SourceEvidence outdated = source(AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.OUTDATED);
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of(outdated));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(decision.overallFreshnessStatus()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(decision.warnings()).anyMatch(w -> w.contains("desactualizada"));
    }

    @Test
    void supported_withoutMaterialDiversity_becomesComLimitacoes() {
        SourceEvidence a = source(AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.SAME_CORE, FreshnessStatus.CURRENT);
        SourceEvidence b = source(AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.SAME_CORE, FreshnessStatus.CURRENT);
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of(a, b));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.limitations()).anyMatch(l -> l.contains("diversidade material"));
    }

    @Test
    void partiallySupported_isComLimitacoes_andParecerSuggested() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.PARTIALLY_SUPPORTED, false,
                List.of(legalStrong()));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    void insufficientContext_withoutSources_isRespostaLimite_parecerRequired_notEmpty() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.INSUFFICIENT_CONTEXT, false, List.of());

        assertThat(decision.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        // Resposta-limite não é não-resposta: dá orientação de próximos passos e limitações.
        assertThat(decision.limitations()).isNotEmpty();
        assertThat(decision.nextSteps()).anyMatch(s -> s.contains("Reformular"));
    }

    @Test
    void insufficientContext_withSources_keepsParecerSuggested_notRequired() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.INSUFFICIENT_CONTEXT, false,
                List.of(legalStrong()));

        assertThat(decision.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    void rejectedUnsupported_isRespostaLimite_parecerRequired_notError() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.REJECTED_UNSUPPORTED, true, List.of());

        assertThat(decision.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        // Pedido de parecer não é erro: há próximos passos accionáveis.
        assertThat(decision.nextSteps()).anyMatch(s -> s.contains("Pedido de parecer"));
    }

    @Test
    void requiresHumanReview_withSources_isComLimitacoes_parecerSuggested_notError() {
        AnswerDecision decision = service.decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, true,
                List.of(legalStrong()), List.of(), "Mensagem de validação.");

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(decision.warnings()).contains("Mensagem de validação.");
    }

    @Test
    void requiresHumanReview_withoutSources_escalatesParecerToRequired() {
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, true, List.of());

        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
    }

    @Test
    void onlyDerivativeReplicatedSources_emitDiversityLimitation() {
        SourceEvidence derivative = source(AuthorityLevel.INTERNAL_CURATED,
                SourceRole.DERIVATIVE_REPLICATED, SourceQuality.ADEQUATE,
                SourceDiversity.SAME_CORE, FreshnessStatus.CURRENT);
        AnswerDecision decision = decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false, List.of(derivative));

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.limitations()).anyMatch(l -> l.contains("derivadas"));
    }

    @Test
    void nullSupportStatus_defaultsToRespostaLimite_parecerSuggested() {
        AnswerDecision decision = decide(null, false, List.of());

        assertThat(decision.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(decision.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(decision.confidenceSummary()).isNotBlank();
    }

    @Test
    void baseLimitations_arePreserved_deduplicated() {
        AnswerDecision decision = service.decide(
                com.knowledgeflow.ai.grounding.AnswerSupportStatus.SUPPORTED, false,
                List.of(legalStrong()), List.of("Limitação a montante.", "Limitação a montante."), null);

        assertThat(decision.limitations()).containsOnlyOnce("Limitação a montante.");
    }
}
