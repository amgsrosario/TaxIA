package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.research.service.ResearchAnswerPolicy.Decision;
import com.knowledgeflow.research.service.ResearchAnswerPolicy.Inputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Aditamento Operacional §2: mínimos da resposta com evidência não promovida. */
class ResearchAnswerPolicyTest {

    private final ResearchAnswerPolicy policy = new ResearchAnswerPolicy();

    @Test
    @DisplayName("Evidência aceite e proposição ancorada → no máximo COM_LIMITACOES + SUGGESTED")
    void bestCaseIsDocumentedWithLimitations() {
        Decision d = policy.decide(new Inputs(1, 1, false, false));
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(d.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    @DisplayName("Nunca CONSULTA_DOCUMENTADA nem parecer NONE, em nenhuma combinação")
    void neverPlainDocumentedOrNone() {
        for (int ev = 0; ev <= 2; ev++) {
            for (int props = 0; props <= 2; props++) {
                for (boolean conflict : new boolean[] {false, true}) {
                    for (boolean uncertain : new boolean[] {false, true}) {
                        Decision d = policy.decide(new Inputs(ev, props, conflict, uncertain));
                        assertThat(d.answerType()).isNotEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
                        assertThat(d.parecerRequirement()).isNotEqualTo(ParecerRequirement.NONE);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Conflito material ou vigência incerta → RESPOSTA_LIMITE + REQUIRED")
    void conflictOrUncertainValidityRequiresParecer() {
        assertThat(policy.decide(new Inputs(2, 2, true, false)))
                .extracting(Decision::answerType, Decision::parecerRequirement)
                .containsExactly(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED);
        assertThat(policy.decide(new Inputs(1, 1, false, true)))
                .extracting(Decision::answerType, Decision::parecerRequirement)
                .containsExactly(AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED);
    }

    @Test
    @DisplayName("Insuficiência de fundamento → RESPOSTA_LIMITE + SUGGESTED")
    void insufficiencyIsLimit() {
        assertThat(policy.decide(new Inputs(0, 0, false, false)).answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(policy.decide(new Inputs(1, 0, false, false)).answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(policy.groundingRejected().answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }
}
