package com.knowledgeflow.research.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.FoundationGovernance;
import com.knowledgeflow.ai.documented.FoundationGovernance.FoundationRisk;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.research.eligibility.ResearchEligibility.Decision;
import com.knowledgeflow.research.eligibility.ResearchEligibility.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regra de elegibilidade da especificação KF-CONV-01 v13, §3.3. */
class ResearchEligibilityTest {

    private static FoundationRisk risk(KnowledgeRiskLevel level) {
        return new FoundationRisk(UUID.randomUUID(), "Q&A", level, false, level == null ? "sem metadados" : null);
    }

    /** Sem fundamentos (Resposta-limite antes do LLM): o caso típico de falta de contexto. */
    private static final FoundationGovernance NO_FOUNDATIONS = FoundationGovernance.of(List.of());
    private static final FoundationGovernance CRITICAL = FoundationGovernance.of(List.of(risk(KnowledgeRiskLevel.CRITICAL)));
    private static final FoundationGovernance CRITICAL_WITH_UNKNOWN =
            FoundationGovernance.of(List.of(risk(null), risk(KnowledgeRiskLevel.CRITICAL)));

    private static Decision eligibleInputs(FoundationGovernance g) {
        return ResearchEligibility.evaluate(true, true, AnswerType.RESPOSTA_LIMITE,
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, g);
    }

    @Test
    void limitAnswerForInsufficientContextWithoutCriticalIsEligible() {
        assertThat(eligibleInputs(NO_FOUNDATIONS)).isEqualTo(new Decision(true, Reason.ELIGIBLE));
    }

    @Test
    void exactlyOneCombinationOfAnswerTypeAndSupportStatusIsEligible() {
        List<String> eligible = new ArrayList<>();
        for (AnswerType t : AnswerType.values()) {
            for (AnswerSupportStatus s : AnswerSupportStatus.values()) {
                for (FoundationGovernance g : List.of(NO_FOUNDATIONS, CRITICAL)) {
                    Decision d = ResearchEligibility.evaluate(true, true, t, s, g);
                    assertThat(d.eligible()).isEqualTo(d.reason() == Reason.ELIGIBLE);
                    if (d.eligible()) eligible.add(t + "/" + s + "/" + (g == CRITICAL ? "CRITICAL" : "-"));
                }
            }
        }
        assertThat(eligible).containsExactly("RESPOSTA_LIMITE/INSUFFICIENT_CONTEXT/-");
    }

    @Test
    void otherAnswerTypesIncludingPedidoDeParecerAreNotEligible() {
        for (AnswerType t : List.of(AnswerType.CONSULTA_DOCUMENTADA, AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES,
                AnswerType.PEDIDO_DE_PARECER)) {
            assertThat(ResearchEligibility.evaluate(true, true, t, AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    NO_FOUNDATIONS)).isEqualTo(new Decision(false, Reason.NOT_LIMIT_ANSWER));
        }
    }

    @Test
    void otherSupportStatusesIncludingRequiresHumanReviewAreNotEligible() {
        for (AnswerSupportStatus s : List.of(AnswerSupportStatus.SUPPORTED, AnswerSupportStatus.PARTIALLY_SUPPORTED,
                AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, AnswerSupportStatus.REJECTED_UNSUPPORTED)) {
            assertThat(ResearchEligibility.evaluate(true, true, AnswerType.RESPOSTA_LIMITE, s, NO_FOUNDATIONS))
                    .isEqualTo(new Decision(false, Reason.NOT_INSUFFICIENT_CONTEXT));
        }
    }

    @Test
    void criticalFoundationSuppressionIsNotEligibleEvenWithUnknownRisk() {
        assertThat(eligibleInputs(CRITICAL)).isEqualTo(new Decision(false, Reason.CRITICAL_SUPPRESSION));
        assertThat(eligibleInputs(CRITICAL_WITH_UNKNOWN)).isEqualTo(new Decision(false, Reason.CRITICAL_SUPPRESSION));
    }

    @Test
    void nonCriticalGovernanceDoesNotAddConditions() {
        // §3.3 só exclui a supressão CRITICAL (ADR-007); outros níveis, risco desconhecido, validação humana
        // agregada ou governação não avaliada não acrescentam condições.
        for (FoundationGovernance g : List.of(
                FoundationGovernance.of(List.of(risk(KnowledgeRiskLevel.LOW))),
                FoundationGovernance.of(List.of(risk(KnowledgeRiskLevel.HIGH))),
                FoundationGovernance.of(List.of(risk(null))),
                FoundationGovernance.of(List.of(new FoundationRisk(UUID.randomUUID(), "Q&A", KnowledgeRiskLevel.MEDIUM,
                        true, null))),
                FoundationGovernance.notAssessed())) {
            assertThat(eligibleInputs(g).reason()).isEqualTo(Reason.ELIGIBLE);
        }
    }

    @Test
    void integrationDisabledOrNonAdminIsNotEligible() {
        assertThat(ResearchEligibility.evaluate(false, true, AnswerType.RESPOSTA_LIMITE,
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, NO_FOUNDATIONS))
                .isEqualTo(new Decision(false, Reason.INTEGRATION_DISABLED));
        assertThat(ResearchEligibility.evaluate(true, false, AnswerType.RESPOSTA_LIMITE,
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, NO_FOUNDATIONS))
                .isEqualTo(new Decision(false, Reason.NOT_ADMIN));
        // A configuração é verificada primeiro: desligada, nada mais é avaliado.
        assertThat(ResearchEligibility.evaluate(false, false, null, null, null).reason())
                .isEqualTo(Reason.INTEGRATION_DISABLED);
    }

    @Test
    void missingInputsFailClosed() {
        assertThat(ResearchEligibility.evaluate(true, true, null, AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                NO_FOUNDATIONS).reason()).isEqualTo(Reason.MISSING_INPUT);
        assertThat(ResearchEligibility.evaluate(true, true, AnswerType.RESPOSTA_LIMITE, null, NO_FOUNDATIONS).reason())
                .isEqualTo(Reason.MISSING_INPUT);
        assertThat(ResearchEligibility.evaluate(true, true, AnswerType.RESPOSTA_LIMITE,
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, null).reason()).isEqualTo(Reason.MISSING_INPUT);
    }

    @Test
    void decisionIsDeterministic() {
        for (int i = 0; i < 3; i++) {
            assertThat(eligibleInputs(NO_FOUNDATIONS)).isEqualTo(eligibleInputs(NO_FOUNDATIONS));
            assertThat(eligibleInputs(CRITICAL)).isEqualTo(eligibleInputs(CRITICAL));
        }
    }

    @Test
    void decisionRecordRejectsIncoherentValues() {
        assertThatThrownBy(() -> new Decision(true, Reason.NOT_ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(false, Reason.ELIGIBLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(false, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
