package com.knowledgeflow.ai.grounding.scope;

import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.CALCULO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.HABITACAO_PROPRIA;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.HERANCA_INDIVISA;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.INQUILINO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.PAGAMENTO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.PENSAO_ESTRANGEIRA;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.RECLAMACAO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.RETENCAO_OBRIGATORIA;
import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Detecção determinística dos marcadores de aplicabilidade (M4-SCOPE-V2): positivos e negativos. */
class ApplicabilityMarkerDetectorTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();
    private final ApplicabilityMarkerDetector detector = new ApplicabilityMarkerDetector();

    private Set<ApplicabilityMarker> detect(String question) {
        return detector.detect(question, classifier.classify(question));
    }

    @ParameterizedTest(name = "{1} ← {0}")
    @CsvSource(delimiter = '|', textBlock = """
            Sou inquilino; posso abater as obras que paguei?                      | INQUILINO
            O arrendatário pode deduzir alguma coisa?                             | INQUILINO
            Obras na minha habitação própria e permanente contam?                 | HABITACAO_PROPRIA
            Despesas da casa própria são dedutíveis?                              | HABITACAO_PROPRIA
            Os herdeiros de uma herança indivisa como declaram o imóvel?          | HERANCA_INDIVISA
            O cabeça de casal tem de fazer o quê?                                 | HERANCA_INDIVISA
            Tenho uma pensão paga por uma instituição estrangeira                 | PENSAO_ESTRANGEIRA
            Recebo pensões do estrangeiro, posso pedir retenção?                  | PENSAO_ESTRANGEIRA
            Como se calcula o valor do imposto?                                   | CALCULO
            Como é calculado o AIMI de um casal?                                  | CALCULO
            Até quando posso reclamar da liquidação?                              | RECLAMACAO
            Como impugnar a liquidação?                                           | RECLAMACAO
            Posso fazer o pagamento em prestações?                                | PAGAMENTO
            Quando tenho de pagar o imposto?                                      | PAGAMENTO
            A entidade é obrigada a fazer retenção na fonte?                      | RETENCAO_OBRIGATORIA
            A retenção é obrigatória neste caso?                                  | RETENCAO_OBRIGATORIA
            Existe obrigação de reter sobre o complemento?                        | RETENCAO_OBRIGATORIA
            """)
    void detectsMarker(String question, ApplicabilityMarker expected) {
        assertThat(detect(question)).contains(expected);
    }

    @Test
    void incidentalWordsDoNotTriggerMarkers() {
        // "calculado em conjunto / com as casas" descreve a opção conjunta, não o cálculo do imposto
        assertThat(detect("Queremos que o adicional do IMI seja calculado em conjunto; até quando optamos?"))
                .doesNotContain(CALCULO);
        assertThat(detect("Queremos o AIMI calculado conjuntamente")).doesNotContain(CALCULO);
        // prestação de serviços não é pagamento em prestações; "pagar" isolado não conta
        assertThat(detect("A prestação de serviços tem IVA?")).doesNotContain(PAGAMENTO);
        assertThat(detect("Todos os anos tenho IRS a pagar com as minhas duas pensões")).doesNotContain(PAGAMENTO);
        // herança sem indivisão, "casa onde moro", pensão sem estrangeiro, pedido voluntário de retenção
        assertThat(detect("Recebi uma casa por herança e arrendo-a")).doesNotContain(HERANCA_INDIVISA);
        assertThat(detect("Obras na casa onde moro")).doesNotContain(HABITACAO_PROPRIA);
        assertThat(detect("Tenho duas pensões nacionais a 0%")).doesNotContain(PENSAO_ESTRANGEIRA);
        assertThat(detect("Posso pedir que me retenham IRS todos os meses?")).doesNotContain(RETENCAO_OBRIGATORIA);
        assertThat(detect("O senhorio pode deduzir o condomínio às rendas?")).isEmpty();
    }

    @Test
    void tenantMarker_needsTheTenantRole_notAMentionByTheLandlord() {
        assertThat(detect("O inquilino paga-me a renda todos os meses; que gastos meus posso descontar?"))
                .doesNotContain(INQUILINO);
        assertThat(detect("O meu inquilino não paga a renda, posso deduzir as obras?")).doesNotContain(INQUILINO);
        assertThat(detect("Tenho um inquilino, como declaro as rendas?")).doesNotContain(INQUILINO);
        assertThat(detect("Sou senhorio e o arrendatário saiu, que despesas deduzo?")).doesNotContain(INQUILINO);
        assertThat(detect("Como o inquilino deixou de pagar a renda, posso deduzir as rendas em falta?"))
                .doesNotContain(INQUILINO);
        assertThat(detect("O inquilino tem uma dívida de rendas, posso abater nos rendimentos prediais?"))
                .doesNotContain(INQUILINO);
        assertThat(detect("O inquilino pode sublocar? Sou senhorio e quero saber como declarar."))
                .doesNotContain(INQUILINO);
        assertThat(detect("Enquanto arrendatária, posso deduzir alguma coisa?")).contains(INQUILINO);
        assertThat(detect("Como inquilino, posso deduzir alguma coisa?")).contains(INQUILINO);
        assertThat(detect("O inquilino pode deduzir as rendas que paga?")).contains(INQUILINO);
    }

    @Test
    void otherMarkers_ignoreUnrelatedSensesOfTheirWords() {
        assertThat(detect("Posso deduzir as prestações do crédito à habitação?")).doesNotContain(PAGAMENTO);
        assertThat(detect("Recebo prestações sociais; pago IRS?")).doesNotContain(PAGAMENTO);
        assertThat(detect("Fiz pagamentos por conta este ano")).doesNotContain(PAGAMENTO);
        assertThat(detect("O imóvel não é habitação própria, está arrendado")).doesNotContain(HABITACAO_PROPRIA);
        assertThat(detect("Sou herdeiro de um imóvel já partilhado")).doesNotContain(HERANCA_INDIVISA);
        assertThat(detect("Sou estrangeiro e recebo pensão portuguesa")).doesNotContain(PENSAO_ESTRANGEIRA);
        assertThat(detect("Vivo no estrangeiro e recebo pensão portuguesa")).doesNotContain(PENSAO_ESTRANGEIRA);
        assertThat(detect("O imóvel não é a minha habitação própria")).doesNotContain(HABITACAO_PROPRIA);
        assertThat(detect("Como reclamar o reembolso do IRS?")).doesNotContain(RECLAMACAO);
        assertThat(detect("O IMT é calculado com base em que valor?")).contains(CALCULO);
    }

    @Test
    void silentQuestion_hasNoMarkers() {
        assertThat(detect("E os prazos?")).isEmpty();
        assertThat(detector.detect(null, null)).isEmpty();
    }

    @Test
    void severalMarkersInOneQuestion() {
        assertThat(detect("Sou inquilino de uma herança indivisa; posso reclamar?"))
                .contains(INQUILINO, HERANCA_INDIVISA, RECLAMACAO);
    }
}
