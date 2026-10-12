package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M4-GROUNDING-DEADLINE-INSTRUMENT-NEGATION: four classes of false negative of the grounding
 * validator — ordinal deadlines, dropped deadline qualifiers, legal instruments (diplomas) and
 * negated obligations. Deterministic identity only; no semantic, fuzzy, embedding or LLM matching.
 */
class GroundingDeadlineInstrumentNegationTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private final AnswerGroundingValidator validator = new AnswerGroundingValidator(PROPS_REJECT);

    private GroundingValidationResult validate(String context, String answer) {
        return validator.validate(answer, List.of(new RetrievedCase("Fonte", "Pergunta?", context, 0.9)));
    }

    private static List<SensitiveClaim> claims(GroundingValidationResult r, SensitiveClaimType type) {
        return r.allClaims().stream().filter(c -> c.type() == type).toList();
    }

    // ------------------------------------------------------------ obrigatórios A–P

    @ParameterizedTest(name = "{0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "A | A fatura é emitida o mais tardar no 5.º dia útil seguinte. | Emita a fatura até ao 5.º dia útil.",
            "C | Até ao 15.º dia do mês seguinte.                         | A fatura é emitida até ao 15.º dia do mês seguinte.",
            "E | O prazo é de 5 dias úteis.                               | Dispõe de 5 dias úteis.",
            "G | Conservar durante 10 anos civis.                         | O prazo é de 10 anos civis.",
            "I | Nos termos do Decreto-Lei n.º 28/2019.                   | Conforme o DL 28/2019.",
            "J | Nos termos do DL 28/2019.                                | Conforme o Decreto-Lei n.º 28/2019.",
            "N | O prestador não está obrigado a emitir a fatura.         | O prestador não é obrigado a emitir a fatura."
    })
    void supported(String id, String context, String answer) {
        var result = validate(context, answer);
        assertThat(result.rejected()).as(id).isFalse();
        assertThat(result.allClaims()).as(id).isNotEmpty().allMatch(SensitiveClaim::supported);
    }

    @ParameterizedTest(name = "{0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "B | A fatura é emitida o mais tardar no 5.º dia útil seguinte. | Emita a fatura até ao 10.º dia útil.",
            "D | Até ao 15.º dia do mês seguinte.                         | Emita a fatura até ao 5.º dia útil.",
            "F | O prazo é de 5 dias úteis.                               | Dispõe de 5 dias.",
            "H | Conservar durante 10 anos civis.                         | O prazo é de 10 anos.",
            "K | Nos termos do Decreto-Lei n.º 28/2019.                   | Conforme o Decreto-Lei n.º 198/2012.",
            "L | Nos termos do DL 49/2025.                                | Conforme o DL 28/2019.",
            "M | O prestador deve emitir a fatura.                        | O prestador não é obrigado a emitir a fatura.",
            "O | É obrigatório declarar os rendimentos.                   | Não é obrigatório declarar os rendimentos.",
            "P | O senhorio não tem de declarar o contrato.               | O senhorio deve declarar o contrato."
    })
    void rejected(String id, String context, String answer) {
        var result = validate(context, answer);
        assertThat(result.rejected()).as(id).isTrue();
        assertThat(result.unsupportedClaims()).as(id).isNotEmpty();
    }

    // ------------------------------------------------------------ prazos ordinais: formas e fronteiras

    @ParameterizedTest(name = "ordinal \"{0}\" ≡ \"5.º dia útil\"")
    @CsvSource(delimiter = '|', value = {
            "5.º dia útil", "5º dia útil", "5.° dia útil", "5° dia útil", "5.o dia útil",
            "5.º dia útil", "5.º dia útil", "5.º dia  útil", "5.º DIA ÚTIL", "5.º dia util"
    })
    void ordinalTypographicVariants(String written) {
        var result = validate("O mais tardar no 5.º dia útil seguinte.", "Até ao " + written + ".");
        assertThat(result.rejected()).isFalse();
        assertThat(claims(result, SensitiveClaimType.DEADLINE)).singleElement()
                .satisfies(c -> assertThat(c.supported()).isTrue());
    }

    @ParameterizedTest(name = "contexto \"{0}\" não suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "5.º dia útil              | 15.º dia útil",
            "15.º dia útil             | 5.º dia útil",
            "15.º dia útil             | 1.º dia útil",
            "5.º dia útil              | 5.º dia",
            "5.º dia                   | 5.º dia útil",
            "15.º dia do mês seguinte  | 15.º dia",
            "15.º dia do mês seguinte  | 15.º dia do mês anterior",
            "15.º dia do mês seguinte  | 15.º dia útil do mês seguinte",
            "5.º dia útil              | 5 dias úteis",
            "5 dias úteis              | 5.º dia útil",
            "15.º dia do mês seguinte  | 15 dias"
    })
    void ordinalIdentityIsWhole(String context, String claim) {
        var result = validate("Prazo: " + context + ".", "Prazo: " + claim + ".");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("ordinal: '5.º dia útil seguinte' suporta '5.º dia útil' (\"seguinte\" isolado não é qualificador)")
    void seguinteAloneIsNotAQualifier() {
        var result = validate("O mais tardar no 5.º dia útil seguinte ao do momento em que o imposto é devido.",
                "A fatura é emitida até ao 5.º dia útil após a prestação.");
        assertThat(result.rejected()).isFalse();
    }

    // ------------------------------------------------------------ qualificadores (cardinais)

    @ParameterizedTest(name = "contexto \"{0}\" suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "30 dias                  | 30 dias",
            "1 dia útil               | 1 dia útil",
            "5 dias úteis             | 5 dias uteis",
            "10 dias seguidos         | 10 dias seguidos",
            "3 meses                  | 3 meses",
            "(10 anos civis)          | 10 anos civis",
            "10 anos civis subsequentes | 10 anos civis",
            "10 anos subsequentes     | 10 anos",
            "5 dias úteis        | 5 dias úteis"
    })
    void sameCardinalIdentityIsSupported(String context, String claim) {
        var result = validate("O prazo é de " + context + ".", "O prazo é de " + claim + ".");
        assertThat(result.rejected()).isFalse();
        assertThat(claims(result, SensitiveClaimType.DEADLINE)).singleElement()
                .satisfies(c -> assertThat(c.supported()).isTrue());
    }

    @ParameterizedTest(name = "contexto \"{0}\" não suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "5 dias úteis             | 5 dias",
            "5 dias                   | 5 dias úteis",
            "10 anos civis            | 10 anos",
            "10 anos                  | 10 anos civis",
            "10 dias seguidos         | 10 dias consecutivos",
            "10 dias seguidos         | 10 dias",
            "30 dias                  | 3 dias",
            "130 dias                 | 30 dias",
            "1,5 anos                 | 5 anos",
            "30 dias                  | 30 meses"
    })
    void differentCardinalIdentityIsRejected(String context, String claim) {
        var result = validate("O prazo é de " + context + ".", "O prazo é de " + claim + ".");
        assertThat(result.rejected()).isTrue();
    }

    // ------------------------------------------------------------ diplomas

    @ParameterizedTest(name = "\"{0}\" ≡ \"Decreto-Lei n.º 28/2019\"")
    @CsvSource(delimiter = '|', value = {
            "Decreto-Lei n.º 28/2019", "Decreto-Lei nº 28/2019", "Decreto-Lei n.° 28/2019", "Decreto-Lei n. 28/2019",
            "Decreto-Lei 28/2019", "Decreto Lei n.º 28/2019", "Decreto -Lei n.º 28/2019", "decreto-lei n.º 28/2019",
            "DL n.º 28/2019", "DL 28/2019", "D.L. nº 28/2019", "D.L. 28/2019", "Decreto-Lei n.º 28/2019"
    })
    void decreeLawFormsShareIdentity(String written) {
        var result = validate("Nos termos do Decreto-Lei n.º 28/2019, de 15 de fevereiro.", "Conforme o " + written + ".");
        assertThat(result.rejected()).isFalse();
        assertThat(claims(result, SensitiveClaimType.LEGAL_REFERENCE)).singleElement()
                .satisfies(c -> assertThat(c.supported()).isTrue());
    }

    @ParameterizedTest(name = "contexto \"{0}\" não suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Decreto-Lei n.º 28/2019     | Decreto-Lei n.º 28/2020",
            "Decreto-Lei n.º 28/2019     | Decreto-Lei n.º 128/2019",
            "Decreto-Lei n.º 28/2019     | Decreto-Lei n.º 2/2019",
            "Decreto-Lei n.º 28/2019     | Decreto-Lei n.º 28",
            "Decreto-Lei n.º 28          | Decreto-Lei n.º 28/2019",
            "Decreto-Lei n.º 442-A/88    | Decreto-Lei n.º 442-B/88",
            "Decreto-Lei n.º 442-A/88    | Decreto-Lei n.º 442/88",
            "Decreto-Lei n.º 442-A/88    | Decreto-Lei n.º 442-A/1988",
            "Decreto-Lei n.º 28/2019     | Portaria n.º 28/2019",
            "Portaria n.º 195/2020       | Despacho n.º 195/2020"
    })
    void differentInstrumentIsRejected(String context, String claim) {
        var result = validate("Nos termos do " + context + ".", "Conforme o " + claim + ".");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("diploma: sufixo e ano preservados — 'Decreto-Lei n.º 442-A/88' suporta 'DL 442-A/88'")
    void suffixedDecreeIsSupported() {
        var result = validate("Aprovado pelo Decreto-Lei n.º 442-A/88, de 30 de novembro.", "Aprovado pelo DL 442-A/88.");
        assertThat(result.rejected()).isFalse();
    }

    @Test
    @DisplayName("diploma: a forma antes não detectada 'Decreto-Lei n.º X' errada passa a ser rejeitada")
    void wrongDecreeWithOrdinalNumberSignIsRejected() {
        var result = validate("Decreto-Lei n.º 28/2019, art. 19.º: 10 anos.",
                "Deve conservar os documentos durante 10 anos, nos termos do Decreto-Lei n.º 198/2012.");
        assertThat(result.rejected()).isTrue();
        assertThat(result.unsupportedClaims()).extracting(SensitiveClaim::text)
                .containsExactly("Decreto-Lei n.º 198/2012");
    }

    // ------------------------------------------------------------ obrigação negada

    @ParameterizedTest(name = "contexto \"{0}\" NÃO suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "O prestador deve emitir a fatura.                 | O prestador não está obrigado a emitir a fatura.",
            "O prestador deve emitir a fatura.                 | O prestador não tem de emitir a fatura.",
            "O prestador deve emitir a fatura.                 | O prestador não deve emitir a fatura.",
            "O prestador tem de emitir a fatura.               | O prestador não é obrigado a emitir a fatura.",
            "A fatura deve ser emitida no prazo legal.         | O prestador não é obrigado a emitir a fatura.",
            "O prestador não deve emitir a fatura.             | O prestador deve emitir a fatura.",
            "O prestador não está obrigado a emitir a fatura.  | O prestador tem de emitir a fatura.",
            "O prestador não é obrigado a emitir a fatura.     | O prestador é obrigado a emitir a fatura."
    })
    void oppositePolarityIsRejected(String context, String answer) {
        var result = validate(context, answer);
        assertThat(result.rejected()).isTrue();
        assertThat(claims(result, SensitiveClaimType.LEGAL_OBLIGATION)).anyMatch(c -> !c.supported());
    }

    @ParameterizedTest(name = "contexto \"{0}\" suporta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "O prestador não é obrigado a emitir a fatura.     | O prestador não está obrigado a emitir a fatura.",
            "Os prestadores não são obrigados a emitir fatura. | O prestador não é obrigado a emitir fatura.",
            "O prestador não tem de emitir a fatura.           | O prestador não é obrigado a emitir a fatura."
    })
    void sameNegativeFamilyIsSupported(String context, String answer) {
        var result = validate(context, answer);
        assertThat(result.rejected()).isFalse();
        assertThat(claims(result, SensitiveClaimType.LEGAL_OBLIGATION)).isNotEmpty().allMatch(SensitiveClaim::supported);
    }

    @Test
    @DisplayName("'não é obrigado a emitir' sem nenhuma evidência no contexto é rejeitado (forma estrita)")
    void negatedObligationWithoutEvidenceIsRejected() {
        var result = validate("A fatura contém o NIF do adquirente.", "O prestador não é obrigado a emitir fatura.");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("'não deve emitir' (proibição) não é suportado por 'não é obrigado a emitir'")
    void prohibitionIsNotNotObliged() {
        var result = validate("O prestador não é obrigado a emitir a fatura.", "O prestador não deve emitir a fatura.");
        assertThat(result.rejected()).isFalse(); // contradiction-only: no opposite-polarity evidence
        assertThat(claims(result, SensitiveClaimType.LEGAL_OBLIGATION)).isEmpty();
    }

    @ParameterizedTest(name = "sem contradição no contexto, \"{1}\" não é afirmação (comportamento anterior)")
    @CsvSource(delimiter = '|', value = {
            "O prestador deve emitir a fatura. | Em caso de dúvida, deve consultar um contabilista certificado.",
            "O prestador deve emitir a fatura. | Para isso tem de aceder ao Portal das Finanças.",
            "O prestador deve emitir a fatura. | O prestador deve emitir a fatura.",
            "O prestador deve emitir a fatura. | A empresa é obrigada a emitir a fatura.",
            "O prestador não tem de emitir a fatura. | Não se esqueça: não deve confiar em prazos de memória.",
            "O prestador deve emitir a fatura. | O prestador não tem, contudo, de emitir duas faturas."
    })
    void contradictionOnlyFormsAreSilentWithoutContradiction(String context, String answer) {
        var result = validate(context, answer);
        assertThat(result.rejected()).isFalse();
    }

    // ------------------------------------------------------------ regressão G1 (fixture, não publicada)

    private static final String G1 =
            "O artigo 125.º, n.º 1, do Código do IRC sujeita os sujeitos passivos com sede ou direção efetiva em "
            + "território nacional, bem como os que aí possuam estabelecimento estável, às obrigações de faturação e de "
            + "conservação de livros, registos e respetivos documentos de suporte nos termos previstos no Código do IVA e "
            + "no Decreto-Lei n.º 28/2019, de 15 de fevereiro.\n\nNos termos do artigo 19.º, n.º 1, do Decreto-Lei n.º "
            + "28/2019, os sujeitos passivos são obrigados a arquivar e conservar em boa ordem todos os livros, registos e "
            + "respetivos documentos de suporte por um prazo de 10 anos, se outro prazo não resultar de disposição "
            + "especial.\n\nSempre que o sujeito passivo exerça um direito cujo prazo seja superior a esses 10 anos, a "
            + "obrigação de arquivo e conservação mantém-se até ao termo do prazo de caducidade relativo à liquidação dos "
            + "impostos correspondentes (artigo 19.º, n.º 2).";

    @ParameterizedTest(name = "G1 aceita: {0}")
    @CsvSource(delimiter = '|', value = {
            "Para efeitos de IRC, a empresa deve conservar os livros, registos e documentos de suporte durante 10 anos (artigo 125.º, n.º 1, do CIRC e artigo 19.º, n.º 1, do Decreto-Lei n.º 28/2019).",
            "A empresa é obrigada a guardar a contabilidade por um prazo de 10 anos, nos termos do art. 19.º do DL 28/2019.",
            "Tem de conservar os registos durante 10 anos (artigo 19.º do Decreto-Lei n.º 28/2019, de 15 de fevereiro)."
    })
    void g1CorrectAnswersAccepted(String answer) {
        var result = validate(G1, answer);
        assertThat(result.rejected()).isFalse();
    }

    @ParameterizedTest(name = "G1 rejeita: {0}")
    @CsvSource(delimiter = '|', value = {
            "Deve conservar os documentos durante 10 anos, nos termos do Decreto-Lei n.º 198/2012.",
            "Deve conservar os documentos durante 10 anos, nos termos do DL 49/2025.",
            "Deve conservar a contabilidade durante 12 anos (artigo 19.º do Decreto-Lei n.º 28/2019).",
            "Deve conservar a contabilidade durante 10 anos civis (artigo 19.º do Decreto-Lei n.º 28/2019).",
            "A empresa não está obrigada a conservar os documentos (artigo 125.º do CIRC)."
    })
    void g1WrongAnswersRejected(String answer) {
        var result = validate(G1, answer);
        assertThat(result.rejected()).isTrue();
    }

    // ------------------------------------------------------------ regressão G2 (fixture, não publicada)

    private static final String G2 =
            "O artigo 36.º, n.º 1, alínea a), do Código do IVA determina que a fatura deve ser emitida o mais tardar no "
            + "5.º dia útil seguinte ao do momento em que o imposto é devido nos termos do artigo 7.º. Nas prestações de "
            + "serviços, o imposto é devido no momento da sua realização (artigo 7.º, n.º 1, alínea b)). Assim, na regra "
            + "geral, a fatura de um serviço deve ser emitida até ao 5.º dia útil seguinte ao dia em que o serviço foi "
            + "realizado, isto é, até 5 dias úteis depois desse dia.\n\nNos termos da alínea c) do mesmo número, a fatura "
            + "deve ser emitida na data do recebimento quando haja pagamentos relativos a uma prestação de serviços ainda "
            + "não efetuada (adiantamentos) ou quando o pagamento coincida com o momento em que o imposto é devido.\n\n"
            + "Têm regras próprias, fora desta resposta: as prestações intracomunitárias de serviços tributáveis noutro "
            + "Estado-Membro, cuja fatura deve ser emitida o mais tardar no 15.º dia do mês seguinte àquele em que o "
            + "imposto é devido (artigo 36.º, n.º 1, alínea b)); os serviços de carácter continuado (artigo 7.º, n.º 3); e "
            + "as faturas globais (artigo 36.º, n.º 2).";

    @ParameterizedTest(name = "G2 aceita: {0}")
    @CsvSource(delimiter = '|', value = {
            "Na regra geral, a fatura deve ser emitida o mais tardar no 5.º dia útil seguinte ao momento em que o serviço é realizado (artigo 36.º, n.º 1, alínea a), e artigo 7.º, n.º 1, alínea b), do CIVA).",
            "Tem até 5 dias úteis depois de prestar o serviço para emitir a fatura (art. 36.º do CIVA).",
            "O prestador tem de emitir a fatura até ao 5.º dia útil seguinte ao dia em que o serviço foi realizado.",
            "Nas prestações intracomunitárias de serviços, a fatura é emitida até ao 15.º dia do mês seguinte (artigo 36.º, n.º 1, alínea b))."
    })
    void g2CorrectAnswersAccepted(String answer) {
        var result = validate(G2, answer);
        assertThat(result.rejected()).isFalse();
    }

    @ParameterizedTest(name = "G2 rejeita: {0}")
    @CsvSource(delimiter = '|', value = {
            "A fatura deve ser emitida o mais tardar no 10.º dia útil seguinte ao da prestação do serviço (artigo 36.º do CIVA).",
            "A fatura deve ser emitida no prazo de 30 dias após a prestação do serviço.",
            "A fatura deve ser emitida no prazo de 5 dias após a prestação do serviço (artigo 36.º do CIVA).",
            "Não é obrigado a emitir fatura depois de prestar o serviço; basta emitir recibo.",
            "Nas prestações intracomunitárias, a fatura é emitida até ao 5.º dia do mês seguinte."
    })
    void g2WrongAnswersRejected(String answer) {
        var result = validate(G2, answer);
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("G2 (limite documentado): 'não tem de emitir' não é contradito pela voz passiva 'deve ser emitida'")
    void passiveVoiceIsNotContradictionEvidence() {
        var result = validate(G2, "O prestador não tem de emitir a fatura depois de prestar o serviço.");
        assertThat(result.rejected()).isFalse();
    }

    // ------------------------------------------------------------ revisão independente (regressões)

    private GroundingValidationResult validateWithQuestion(String question, String content, String answer) {
        return validator.validate(answer, List.of(new RetrievedCase(question, question, content, 0.9)));
    }

    @Test
    @DisplayName("R1: a pergunta da Q&A ('… tem de emitir fatura …?') não contradiz uma resposta negativa correcta")
    void questionSentenceIsNotContradictionEvidence() {
        var result = validateWithQuestion("O prestador tem de emitir fatura nas vendas a particulares?",
                "Não. Nesse caso a emissão de fatura é dispensada.", "O prestador não tem de emitir fatura nesse caso.");
        assertThat(result.rejected()).isFalse();
        var result2 = validateWithQuestion("O prestador é obrigado a emitir fatura?", "Não.",
                "O prestador não tem de emitir fatura.");
        assertThat(result2.rejected()).isFalse();
    }

    @ParameterizedTest(name = "R2: sem contradição real — contexto \"{0}\" / resposta \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Nas vendas a particulares, não tem de emitir fatura em nome do adquirente. | O vendedor deve emitir fatura simplificada.",
            "Quem fature menos de 10 000 euros não tem de entregar a declaração periódica. | Como ultrapassa esse limite, tem de entregar a declaração periódica.",
            "O prestador não deve liquidar o IVA. | O adquirente tem de liquidar o IVA.",
            "A fatura não tem de ser emitida pelo adquirente. | O fornecedor deve emitir a fatura.",
            "O contribuinte não tem de ser ouvido. | Deve ouvir o técnico.",
            "O prestador não tem de emitir fatura proforma. | O prestador deve emitir fatura no prazo legal.",
            "Se o valor for inferior, o prestador não tem de emitir fatura. | O prestador deve emitir fatura.",
            "O prestador não tem de emitir fatura, salvo se o adquirente a solicitar. | O prestador deve emitir fatura quando o adquirente a solicita.",
            "O prestador não tem de emitir fatura. | O prestador deve emitir fatura se ultrapassar o limite.",
            "Os particulares não emitem faturas. Não têm de emitir fatura. | O vendedor deve emitir fatura.",
            "O adquirente não tem de emitir a fatura. | Deve emitir a fatura.",
            "Não tem de emitir fatura. | O prestador deve emitir fatura.",
            "Neste caso, o prestador não tem de emitir fatura. | O prestador deve emitir fatura.",
            "A entidade não tem de pagar o imposto. | Neste caso a entidade deve pagar o imposto.",
            "Em regra, o prestador não tem de emitir fatura. | O prestador deve emitir fatura.",
            "Em caso de cessação, o prestador não tem de emitir fatura. | O prestador deve emitir fatura.",
            "O prestador não tem de pagar mais imposto. | O prestador deve pagar mai imposto."
    })
    void noRealContradiction(String context, String answer) {
        assertThat(validate(context, answer).rejected()).isFalse();
    }

    @ParameterizedTest(name = "R4: forma no início da frase — \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Não tem de emitir fatura. | Deve emitir fatura.",
            "Não tem de emitir fatura. | Tem de emitir fatura.",
            "Não tem de emitir fatura. | É obrigado a emitir fatura.",
            "O prestador não tem de emitir fatura. | Sim, o prestador deve emitir fatura.",
            "No entanto, o prestador não tem de emitir faturas. | O prestador deve emitir fatura.",
            "O senhorio não tem de declarar. | O senhorio deve declará-lo."
    })
    void sentenceInitialFormsAreDetected(String context, String answer) {
        assertThat(validate(context, answer).rejected()).isTrue();
    }

    @Test
    @DisplayName("R5: um 'tem de' literal noutra frase não suporta 'tem de emitir' contradito")
    void unrelatedLiteralDoesNotSupportContradictedClaim() {
        var result = validate("O prestador não tem de emitir fatura. O IVA tem de ser pago até ao fim do mês.",
                "O prestador tem de emitir fatura.");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("R6: 'não está obrigado a emitir fatura' suporta 'não é obrigado a emiti-la'")
    void cliticInfinitiveAndCopulaAreEquivalent() {
        var result = validate("O prestador não está obrigado a emitir fatura.", "O prestador não é obrigado a emiti-la.");
        assertThat(result.rejected()).isFalse();
    }

    @ParameterizedTest(name = "R3: contexto \"{0}\" / resposta \"{1}\" → suportado={2}")
    @CsvSource(delimiter = '|', value = {
            "A fatura é emitida até ao dia 15 do mês seguinte.   | Até ao 15.º dia do mês seguinte.  | true",
            "A fatura é emitida até ao 15.º dia do mês seguinte. | Até ao dia 15 do mês seguinte.    | true",
            "A fatura é emitida até ao dia 15 do mês seguinte.   | Até ao dia 20 do mês seguinte.    | false",
            "A fatura é emitida até ao dia 15 do mês seguinte.   | Até ao 5.º dia do mês seguinte.   | false",
            "A fatura é emitida até ao dia 15 do mês seguinte.   | Até ao dia 15 do mês anterior.    | false"
    })
    void dayOfMonthPhrasing(String context, String answer, boolean supported) {
        assertThat(validate(context, answer).rejected()).isEqualTo(!supported);
    }

    @ParameterizedTest(name = "R7/R8/R9/R12: contexto \"{0}\" / resposta \"{1}\" → suportado={2}")
    @CsvSource(delimiter = '|', value = {
            "Ofício-Circulado n.º 30.250/2023.   | Ofício-Circulado n.º 30.251/2023.  | false",
            "Ofício-Circulado n.º 30.250/2023.   | Ofício-Circulado n.º 30250/2023.   | true",
            "O prazo é de 2,5 anos.              | O prazo é de 3,5 anos.             | false",
            "O prazo é de 2,5 anos.              | O prazo é de 2.5 anos.             | true",
            "O prazo é de 1.000 dias.            | O prazo é de 1000 dias.            | true",
            "O prazo é de 15 dias.               | O prazo é de 15/30 dias.           | false",
            "A economia é relevante.             | A economia circular 2030 é relevante. | true",
            "O pedido foi analisado.             | O despacho 15 dias depois foi favorável. | false",
            "Decreto-Lei n.º 28/2019.            | Decreto-Lei número 28/2019.        | true",
            "Decreto-Lei n.º 28/2019.            | Decreto-Lei n.o 198/2012.          | false"
    })
    void numbersAndOrdinaryWords(String context, String answer, boolean supported) {
        assertThat(validate(context, answer).rejected()).isEqualTo(!supported);
    }
}
