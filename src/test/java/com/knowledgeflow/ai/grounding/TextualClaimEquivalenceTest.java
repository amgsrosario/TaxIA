package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M4-GROUNDING-TEXTUAL-CLAIM-EQUIVALENCE: textual claims (deductibility, obligation, exemption)
 * are supported by equivalent formulations of the same family and the same polarity — never by
 * the opposite polarity, never by an isolated noun, never across families.
 */
class TextualClaimEquivalenceTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private final AnswerGroundingValidator validator = new AnswerGroundingValidator(PROPS_REJECT);

    /** Real AT-FAQ-5930 content (pilot), as delivered to the LLM in DEMO 2A. */
    private static final String FAQ_5930 =
            "Nos rendimentos prediais da categoria F, o artigo 41.º do CIRS permite deduzir aos rendimentos brutos "
            + "os gastos efectivamente suportados e pagos pelo sujeito passivo para obter ou garantir esses rendimentos, "
            + "relativamente a cada prédio ou parte de prédio.\n\nA dedução exige uma ligação concreta entre a despesa e "
            + "o imóvel gerador do rendimento, bem como suporte documental adequado. A regra deve ser aplicada por imóvel, "
            + "não de forma global e indiferenciada ao conjunto do património do sujeito passivo.\n\nA lei exclui "
            + "expressamente determinados encargos, nomeadamente gastos de natureza financeira, depreciações, despesas "
            + "relativas a mobiliário, electrodomésticos e artigos de conforto ou decoração, bem como o adicional ao IMI.\n\n"
            + "Devem ainda ser analisadas com prudência as despesas de conservação e manutenção, bem como situações em que "
            + "a despesa respeite a períodos anteriores ao arrendamento, incluindo a regra dos 24 meses anteriores, quando aplicável.";

    private static final String Q_5930 =
            "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?";

    private GroundingValidationResult validate(String context, String answer) {
        return validator.validate(answer, List.of(new RetrievedCase("Fonte", "Pergunta?", context, 0.9)));
    }

    private static List<SensitiveClaim> textual(GroundingValidationResult r, SensitiveClaimType type) {
        return r.allClaims().stream().filter(c -> c.type() == type).toList();
    }

    // ------------------------------------------------------------ regressão DEMO 2A

    @Test
    @DisplayName("DEMO 2A: contexto real da 5930 suporta 'O senhorio pode deduzir …'")
    void demo2aRealContextSupportsPositiveDeduction() {
        var result = validator.validate(
                "O senhorio pode deduzir às rendas os gastos efectivamente suportados e pagos para obter esses "
                        + "rendimentos, por imóvel, nos termos do artigo 41.º do CIRS, com excepção de gastos de natureza financeira.",
                List.of(new RetrievedCase("Fonte 5930", "Pergunta?", FAQ_5930, 0.8974)));

        assertThat(result.rejected()).isFalse();
        assertThat(textual(result, SensitiveClaimType.DEDUCTIBILITY))
                .singleElement().satisfies(c -> {
                    assertThat(c.supported()).isTrue();
                    assertThat(c.text()).isEqualTo("pode deduzir");
                    assertThat(c.supportingSourceTitles()).containsExactly("Fonte 5930");
                });
    }

    @Test
    @DisplayName("DEMO 2A: o mesmo contexto NÃO suporta 'O senhorio não pode deduzir …'")
    void demo2aRealContextDoesNotSupportNegativeDeduction() {
        var result = validator.validate("O senhorio não pode deduzir as despesas de conservação.",
                List.of(new RetrievedCase(Q_5930, Q_5930, FAQ_5930, 0.8974)));

        assertThat(result.rejected()).isTrue();
        assertThat(textual(result, SensitiveClaimType.DEDUCTIBILITY))
                .singleElement().satisfies(c -> {
                    assertThat(c.supported()).isFalse();
                    assertThat(c.text()).isEqualTo("não pode deduzir");
                });
    }

    // ------------------------------------------------------------ positivos (mesma família, mesma polaridade)

    @ParameterizedTest(name = "{0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "D1 | O artigo 41.º do CIRS permite deduzir os gastos.           | O senhorio pode deduzir os gastos.",
            "D2 | Podem ser deduzidas as despesas de condomínio.              | A despesa de condomínio é dedutível.",
            "D3 | Os gastos são dedutíveis nos termos da lei.                 | Os sujeitos passivos podem deduzir esses gastos.",
            "D4 | O IVA suportado não é dedutível.                            | O IVA das refeições não é dedutível.",
            "D5 | Não podem ser deduzidos os encargos financeiros.            | O senhorio não pode deduzir os juros.",
            "O1 | O sujeito passivo deve arquivar e conservar os registos.    | É obrigatório conservar os registos. E também é obrigatório conservar.",
            "O2 | O sujeito passivo está obrigado a declarar o IVA.           | O sujeito passivo deve declarar IVA mensalmente.",
            "O3 | Os sujeitos passivos têm de registar-se antes do início.    | A empresa deve registar-se no início da actividade.",
            "O4 | A entrega da declaração é obrigatória.                      | A entrega é obrigatória todos os anos.",
            "O5 | Nesta situação não é obrigatório entregar a declaração.     | Não é obrigatório entregar a declaração.",
            "E1 | As prestações médicas são isentas de IVA.                   | A consulta médica está isenta de IVA.",
            "E2 | As operações ficam isentas nos termos do artigo 9.º.        | Estas operações estão isentas de IVA.",
            "E3 | Esta operação não é isenta de IVA.                          | A operação não está isenta de IVA.",
            "S1 | Estas operações não estão sujeitas a IVA.                   | A operação não está sujeita a IVA."
    })
    void sameFamilySamePolarityIsSupported(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.allClaims()).as(id).isNotEmpty();
        assertThat(result.rejected()).as(id + " " + result.rejectionReason()).isFalse();
    }

    // ------------------------------------------------------------ negativos de polaridade A–F e fronteiras

    @ParameterizedTest(name = "{0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "A  | O artigo 41.º permite deduzir os gastos.                   | O senhorio não pode deduzir os gastos.",
            "B  | O senhorio não pode deduzir os juros.                      | O senhorio pode deduzir os juros.",
            "B2 | Não se pode deduzir o IVA destas despesas.                 | O IVA destas despesas é dedutível.",
            "C  | A entrega da declaração é obrigatória.                     | A entrega da declaração não é obrigatória.",
            "D  | O sujeito passivo não está obrigado a declarar o IVA.      | O sujeito passivo deve declarar IVA.",
            "E  | Os serviços médicos estão isentos de IVA.                  | Os serviços médicos não estão isentos de IVA.",
            "F  | Os serviços médicos não estão isentos de IVA.              | Os serviços médicos estão isentos de IVA.",
            "X1 | A lei exclui expressamente os encargos financeiros.        | Os encargos financeiros são dedutíveis.",
            "X2 | Os encargos financeiros estão excluídos.                   | O senhorio não pode deduzir os encargos financeiros.",
            "N1 | A dedução exige uma ligação concreta ao imóvel.            | O senhorio pode deduzir as despesas.",
            "N2 | Há uma obrigação declarativa anual.                        | É obrigatório entregar a declaração. A entrega é obrigatória.",
            "N3 | O regime de isenção aplica-se em certos casos.             | A actividade está isenta de IVA.",
            "R1 | O sujeito passivo pode entregar a declaração mais cedo.    | A entrega é obrigatória.",
            "R2 | É aconselhável conservar os documentos.                    | É obrigatório conservar os documentos. A conservação é obrigatória.",
            "P1 | O sujeito passivo não deve deduzir este IVA.               | Não é obrigatório deduzir este IVA.",
            "W1 | Os sujeitos passivos devem registar-se.                    | A entrega da declaração é obrigatória para todos.",
            "I1 | Estas operações não estão sujeitas a IVA.                  | Estas operações estão isentas de IVA.",
            "I2 | Estas operações estão isentas de IVA.                      | Estas operações não estão sujeitas a IVA.",
            "I3 | Estas operações estão sujeitas a IVA.                      | Estas operações não estão sujeitas a IVA."
    })
    void oppositePolarityOrOtherFamilyIsRejected(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.rejected()).as(id).isTrue();
    }

    @Test
    @DisplayName("negação com clítico: 'não se pode deduzir' é negativa e não suporta 'pode deduzir'")
    void cliticNegationIsNegative() {
        assertThat(validate("Não se pode deduzir o IVA destas despesas.", "Pode deduzir o IVA destas despesas.").rejected())
                .isFalse(); // "Pode" (maiúscula) não era nem é detectado como claim nas respostas
        assertThat(validate("Não se pode deduzir o IVA destas despesas.", "O sujeito passivo pode deduzir o IVA.").rejected())
                .isTrue();
    }

    @Test
    @DisplayName("polaridade da renúncia: 'não pode renunciar' no contexto nunca suporta 'pode renunciar'")
    void negatedRenunciationKeepsItsPolarity() {
        assertThat(validate("O sujeito passivo não pode renunciar à isenção.", "O sujeito passivo pode renunciar à isenção.")
                .rejected()).isTrue();
        assertThat(validate("O sujeito passivo não pode renunciar à isenção.", "O sujeito passivo não pode renunciar à isenção.")
                .rejected()).isFalse();
    }

    // ------------------------------------------------------------ revisão independente (B1, M1–M6, m1)

    @ParameterizedTest(name = "B1 {0}: contexto negado/incerto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | Estas despesas não as podem deduzir os senhorios.        | Estas despesas são dedutíveis.",
            "b | O senhorio não o pode deduzir.                          | O encargo é dedutível.",
            "c | Este encargo nunca é dedutível.                          | O senhorio pode deduzir este encargo.",
            "d | Este encargo nunca pode ser deduzido.                    | Este encargo é dedutível.",
            "e | O encargo nem sempre é dedutível.                        | O senhorio pode deduzir o encargo.",
            "f | Em caso algum é dedutível este encargo.                  | O senhorio pode deduzir o encargo.",
            "g | O encargo não é, contudo, dedutível.                     | O senhorio pode deduzir o encargo.",
            "h | Não se lhe pode deduzir nada.                            | O senhorio pode deduzir o encargo."
    })
    void negatedOrUncertainContextNeverSupportsPositive(String id, String context, String answer) {
        assertThat(validate(context.strip(), answer.strip()).rejected()).as(id).isTrue();
    }

    @Test
    @DisplayName("B1: negação com clíticos lida como NEGATIVA dos dois lados ('não o pode deduzir')")
    void cliticNegationOnBothSides() {
        assertThat(validate("O senhorio não o pode deduzir.", "O senhorio não pode deduzir o encargo.").rejected()).isFalse();
    }

    @Test
    @DisplayName("incerto ('nem sempre é dedutível'): só a regra exacta anterior, nunca equivalências nem contexto negativo")
    void uncertainClaimUsesOnlyTheExactRule() {
        // mesma frase em ocorrência não negativa: suportada (comportamento da main)
        assertThat(validate("Atenção: o encargo nem sempre é dedutível.", "O encargo nem sempre é dedutível.").rejected())
                .isFalse();
        // formulação equivalente não serve a uma afirmação incerta
        assertThat(validate("O artigo permite deduzir o encargo.", "O encargo nem sempre é dedutível.").rejected())
                .isTrue();
        // contexto negativo nunca suporta
        assertThat(validate("O encargo não é dedutível.", "Em geral, o encargo nem sempre é dedutível.").rejected())
                .isTrue();
    }

    @ParameterizedTest(name = "N1 {0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | Nenhuma das despesas acima referidas e documentadas é dedutível. | O senhorio pode deduzir as despesas.",
            "b | Nenhum encargo previsto no art. 41.º é dedutível.                 | O senhorio pode deduzir o encargo.",
            "c | Nenhum dos encargos do n.º 2 do artigo 41.º é dedutível.          | O senhorio pode deduzir o encargo.",
            "d | Sem documento de suporte, o encargo é dedutível só em parte.      | O senhorio pode deduzir o encargo."
    })
    void negationAnywhereInTheSentenceIsNotPositiveEvidence(String id, String context, String answer) {
        assertThat(validate(context.strip(), answer.strip()).rejected()).as(id).isTrue();
    }

    @ParameterizedTest(name = "N2/N3 {0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | Os sujeitos passivos não residentes estão isentos de IVA.            | Os sujeitos passivos não residentes estão isentos de IVA.",
            "b | O encargo nem sempre é dedutível.                                    | Em geral, para efeitos do IRS, o encargo nem sempre é dedutível.",
            "c | O sujeito passivo que não tenha sede em Portugal deve registar-se.   | A empresa deve registar-se.",
            "d | O senhorio, não sendo residente, pode deduzir os gastos.             | O senhorio pode deduzir os gastos.",
            "e | Está obrigado a entregar a declaração.                               | Se não tiver isenção, está obrigado a entregar a declaração."
    })
    void unrelatedNegationIsNotStricterThanBefore(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.rejected()).as(id + " " + result.rejectionReason()).isFalse();
    }

    @Test
    @DisplayName("frases de fronteira: '41.º', '1.000' e 'art.' não terminam a frase")
    void abbreviationsAndNumbersDoNotEndTheSentence() {
        String text = "Nenhum encargo do art. 41.º, até 1.000 euros, é dedutível.";
        int at = text.indexOf("é dedutível");
        assertThat(TextualClaimEquivalence.sentenceStart(text, at)).isZero();
        String two = "Primeira frase. O encargo é dedutível.";
        assertThat(TextualClaimEquivalence.sentenceStart(two, two.indexOf("é dedutível"))).isEqualTo(15);
    }

    @ParameterizedTest(name = "M1–M3/M6 {0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "M1a | O sujeito passivo não deve liquidar o IVA.               | O sujeito passivo não está obrigado a liquidar o IVA.",
            "M1b | O sujeito passivo não está obrigado a liquidar o IVA.    | O sujeito passivo não deve liquidar IVA.",
            "M1c | O sujeito passivo não deve registar-se.                  | Não é obrigatório registar-se. Assim, não é obrigatório registar-se.",
            "M2a | O sujeito passivo deve registar as faturas.              | A empresa deve registar-se no início.",
            "M2b | O sujeito passivo deve liquidar o imposto do selo.       | A empresa deve liquidar IVA nas vendas.",
            "M3a | O sujeito passivo deve ter em atenção os prazos.         | Assim, é obrigatório ter contabilidade organizada.",
            "M3b | O imposto deve ter sido liquidado.                       | Assim, está obrigado a ter contabilidade.",
            "M3c | Devem ainda ser analisadas com prudência as despesas.    | Assim, está obrigado a ser registado.",
            "M6  | O IRS é deduzido mensalmente pela entidade patronal.     | O senhorio pode deduzir as despesas.",
            "R2b | É aconselhável conservar os documentos.                  | Assim, é obrigatório conservar os documentos.",
            "m1a | O sujeito passivo deve proceder à liquidação do imposto.  | Assim, está obrigado a proceder à retenção na fonte.",
            "m1b | Deve entender-se que o prazo é contínuo.                  | Assim, é obrigatório entender-se o prazo."
    })
    void differentFiscalMeaningIsRejected(String id, String context, String answer) {
        assertThat(validate(context.strip(), answer.strip()).rejected()).as(id).isTrue();
    }

    @ParameterizedTest(name = "M1–M2 {0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "P1 | O sujeito passivo não deve liquidar o IVA nesta operação. | O sujeito passivo não deve liquidar IVA.",
            "P2 | O sujeito passivo tem de registar-se antes de iniciar.    | A empresa deve registar-se no início.",
            "P3 | O sujeito passivo deve liquidar o IVA nas vendas.          | Assim, é obrigatório liquidar o IVA.",
            "P4 | Nesta situação não tem de conservar os talões.             | Assim, não é obrigatório conservar os talões."
    })
    void sameActionSamePolarityIsSupported(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.rejected()).as(id + " " + result.rejectionReason()).isFalse();
    }

    @Test
    @DisplayName("M4: duas afirmações de obrigação com o mesmo verbo são ambas verificadas")
    void sameVerbDifferentPhrasesAreBothChecked() {
        var result = validate("A entrega da declaração é obrigatória.",
                "Assim, é obrigatório conservar os registos e está obrigado a conservar.");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("M5: espaço não separável não impede o suporte")
    void noBreakSpaceIsAWhitespace() {
        assertThat(validate("A entrega é\u00A0obrigatória.", "A entrega é obrigatória.").rejected()).isFalse();
        assertThat(validate("O IVA não\u00A0é dedutível.", "O IVA é dedutível.").rejected()).isTrue();
    }

    // ------------------------------------------------------------ revisão 3 (R1, R2)

    @ParameterizedTest(name = "R1 {0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | O sujeito passivo não residente deve liquidar o IVA.  | O sujeito passivo deve liquidar o IVA.",
            "b | O sujeito passivo deve liquidar o IVA.                | O sujeito passivo não residente deve liquidar o IVA.",
            "c | A empresa sem sede deve declarar o IVA.               | A empresa deve declarar o IVA.",
            "d | A empresa sem sede deve registar se no início.        | A empresa deve registar se no início.",
            "e | A empresa deve registar-se no início.                 | A empresa sem sede deve registar-se no início."
    })
    void deveFormsAreNotStricterThanBefore(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.rejected()).as(id + " " + result.rejectionReason()).isFalse();
    }

    @ParameterizedTest(name = "R2 {0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | Nenhum sujeito passivo pode deduzir este IVA.  | Os sujeitos passivos podem deduzir este IVA.",
            "b | Nenhuma declaração é obrigatória.              | As declarações são obrigatórias.",
            "c | Nenhum serviço está isento.                     | Os serviços estão isentos.",
            "d | As despesas são dedutíveis.                     | Nenhuma despesa é dedutível."
    })
    void uncertainSideNeverUsesNumberOrGenderCollapse(String id, String context, String answer) {
        assertThat(validate(context.strip(), answer.strip()).rejected()).as(id).isTrue();
    }

    @Test
    @DisplayName("n1: a regra literal de 'está obrigado a' mantém o 'a' (paridade com a main)")
    void obligedToKeepsItsPreposition() {
        assertThat(validate("Está obrigado, nos termos da lei, ao pagamento.", "Se não for isento, está obrigado a pagar.")
                .rejected()).isTrue();
        assertThat(validate("Está obrigado a pagar o imposto.", "Se não for isento, está obrigado a pagar.").rejected())
                .isFalse();
    }
}
