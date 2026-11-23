package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * KF-PILOT/IT-2: a verificação da passagem citada lê a unidade legal pela ordem do documento. Só deixa de rejeitar
 * citações literais e contíguas de «enunciado + alínea» da mesma unidade; tudo o resto continua rejeitado.
 */
class EntailmentCitationOrderTest {

    private static final String CHAPEAU = "Estão isentas do imposto:";
    private static final String ANCHOR = "As prestações de serviços efetuadas no exercício das profissões de médico, odontologista, "
            + "psicólogo, parteiro, enfermeiro e outras profissões paramédicas";
    private static final String STATEMENT = "As prestações de serviços efetuadas por um psicólogo no exercício da profissão estão isentas de IVA";

    private static String civa9;
    private static String art78;
    private static String unit;
    private static String contiguous;
    private final EntailmentGate gate = new EntailmentGate();

    @BeforeAll
    static void load() throws IOException {
        civa9 = resource("civa-9-real07-content.txt");
        art78 = resource("cirs-78-real07-content.txt");
        unit = SupportVerifier.unitContext(ANCHOR, civa9);
        contiguous = SupportVerifier.contiguousUnit(ANCHOR, civa9);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = EntailmentCitationOrderTest.class.getResourceAsStream("/kf-m02/real08/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean verified(String citation) {
        return gate.citationVerified(citation, STATEMENT, civa9, unit, contiguous);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1) " + ANCHOR + ";",
            ANCHOR,
            CHAPEAU,
            CHAPEAU + "\n1) " + ANCHOR + ";",
            CHAPEAU + " 1) " + ANCHOR + ";",
            CHAPEAU + " 1) As prestações de serviços efetuadas no exercício das profissões de médico",
            "1) As prestações de serviços efetuadas no exercício das profissões de médico",
    })
    @DisplayName("Citações literais da unidade são aceites, incluindo enunciado + alínea contíguos")
    void literalCitationsOfTheUnit(String citation) {
        assertThat(verified(citation)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Estão isentas do imposto as prestações de serviços efetuadas no exercício da profissão de psicólogo",
            CHAPEAU + "\n2) As prestações de serviços médicos e sanitários",
            "2) As prestações de serviços médicos e sanitários e as operações com elas estreitamente conexas",
            "paramédicas; Estão isentas do imposto:",
            "1) " + ANCHOR + "; " + CHAPEAU,
            "Isenções nas operações internas Estão isentas do imposto:",
    })
    @DisplayName("Reformulações, outras alíneas e combinações não contíguas continuam rejeitadas")
    void everythingElseStaysRejected(String citation) {
        assertThat(verified(citation)).isFalse();
    }

    @Test
    @DisplayName("Numa unidade sem enunciado introdutório, uma passagem literal de outro número continua rejeitada")
    void otherNumberOfTheArticleStaysRejected() {
        String n5 = "aplicam-se apenas aos sujeitos passivos residentes em território português";
        String n5Unit = SupportVerifier.unitContext(n5, art78);
        String n2 = "São ainda deduzidos à coleta os pagamentos por conta do imposto";
        assertThat(gate.citationVerified(n5, "As deduções do n.º 1 aplicam-se apenas a residentes", art78, n5Unit)).isTrue();
        assertThat(gate.citationVerified(n2, "As deduções do n.º 1 aplicam-se apenas a residentes", art78, n5Unit)).isFalse();
    }

    @Test
    @DisplayName("Parecer R1-01: passagem literal que cruza unidades, por linha repetida, continua rejeitada")
    void repeatedLineCannotBuildACitationAcrossUnits() {
        String evidence = """
                Artigo teste
                0 - Regime anterior:
                b) Operações anteriores sem benefício;
                1 - Estão isentas as operações:
                a) Prestações médicas em território nacional;
                b) Operações anteriores sem benefício;
                """;
        String anchor = "a) Prestações médicas em território nacional; b) Operações anteriores sem benefício;";
        String u = SupportVerifier.unitContext(anchor, evidence);
        String c = SupportVerifier.contiguousUnit(anchor, evidence);
        String across = "b) Operações anteriores sem benefício; 1 - Estão isentas as operações: a) Prestações médicas em território nacional;";
        assertThat(gate.citationVerified(across, "Estão isentas as prestações médicas", evidence, u, c)).isFalse();
        assertThat(gate.citationVerified("1 - Estão isentas as operações: a) Prestações médicas em território nacional;",
                "Estão isentas as prestações médicas", evidence, u, c)).isTrue();
    }

    @Test
    @DisplayName("Enunciado repetido noutro número: conta só o da ocorrência localizada")
    void repeatedChapeau() {
        String evidence = """
                Artigo teste
                1 - Estão isentas:
                a) Operações do tipo um;
                2 - Estão isentas:
                a) Operações do tipo dois;
                """;
        String anchor = "a) Operações do tipo dois;";
        String u = SupportVerifier.unitContext(anchor, evidence);
        String c = SupportVerifier.contiguousUnit(anchor, evidence);
        assertThat(c).isEqualTo("2 - Estão isentas: a) Operações do tipo dois;");
        assertThat(gate.citationVerified("1 - Estão isentas: a) Operações do tipo um;", "x", evidence, u, c)).isFalse();
        assertThat(gate.citationVerified("2 - Estão isentas: a) Operações do tipo dois;", "x", evidence, u, c)).isTrue();
    }

    @Test
    @DisplayName("Sem enunciado contíguo não há unidade contígua; a assinatura sem ela mantém o comportamento anterior")
    void noContiguousUnit() {
        String n5 = "aplicam-se apenas aos sujeitos passivos residentes em território português";
        assertThat(SupportVerifier.contiguousUnit(n5, art78)).isEmpty();
        assertThat(SupportVerifier.contiguousUnit("3) texto que não existe", civa9)).isEmpty();
        assertThat(gate.citationVerified(CHAPEAU + " 1) " + ANCHOR + ";", STATEMENT, civa9, unit)).isFalse();
    }

    @Test
    @DisplayName("Alínea sem enunciado imediatamente antes: enunciado + alínea não é aceite")
    void itemNotAdjacentToChapeau() {
        String c = SupportVerifier.contiguousUnit("2) As prestações de serviços médicos e sanitários", civa9);
        assertThat(c).isEmpty();
    }

    @Test
    @DisplayName("Parecer R2-01: âncora que atravessa números legais não dá unidade contígua")
    void anchorAcrossNumbers() {
        String evidence = """
                Artigo teste
                1 - Regime anterior:
                a) Operações antigas;
                2 - Estão isentas:
                a) Operações médicas;
                """;
        String anchor = "a) Operações antigas; 2 - Estão isentas: a) Operações médicas;";
        String u = SupportVerifier.unitContext(anchor, evidence);
        String c = SupportVerifier.contiguousUnit(anchor, evidence);
        assertThat(c).isEmpty();
        assertThat(gate.citationVerified("1 - Regime anterior: a) Operações antigas; 2 - Estão isentas:", "x", evidence, u, c)).isFalse();
    }

    @Test
    @DisplayName("Parecer R2-01: alínea idêntica sob enunciados opostos é ambígua: sem unidade contígua")
    void identicalItemUnderOppositeChapeaux() {
        String evidence = """
                Artigo teste
                1 - Não estão isentas:
                a) Operações médicas;
                2 - Estão isentas:
                a) Operações médicas;
                """;
        String anchor = "a) Operações médicas;";
        assertThat(SupportVerifier.contiguousUnit(anchor, evidence)).isEmpty();
        String u = SupportVerifier.unitContext(anchor, evidence);
        assertThat(gate.citationVerified("2 - Estão isentas: a) Operações médicas;", "x", evidence, u, "")).isFalse();
        assertThat(gate.citationVerified("1 - Não estão isentas: a) Operações médicas;", "x", evidence, u, "")).isFalse();
    }

    private static final String ITEM = "a) Prestações de serviços médicos em território nacional;";

    private static EntailmentValidator.Assessment supports(String citation) {
        java.util.Map<EntailmentValidator.Dimension, EntailmentValidator.DimensionStatus> dims = new java.util.EnumMap<>(EntailmentValidator.Dimension.class);
        for (EntailmentValidator.Dimension d : EntailmentValidator.Dimension.values()) dims.put(d, EntailmentValidator.DimensionStatus.CONFORME);
        return new EntailmentValidator.Assessment(EntailmentValidator.Verdict.SUPORTA, dims, java.util.Map.of(), "justificação", citation, null, null);
    }

    private boolean demonstrated(String chapeau, String citation) {
        String evidence = "Artigo teste\n" + chapeau + "\n" + ITEM + "\n";
        String anchor = ITEM;
        return gate.decide(supports(citation), "x", evidence, SupportVerifier.unitContext(anchor, evidence),
                SupportVerifier.contiguousUnit(anchor, evidence), SupportVerifier.normativeUnit(anchor, evidence), List.of()).demonstrated();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1 - Não estão isentas as operações seguintes:",
            "1 - Estão isentas, salvo as operações ocasionais:",
            "1 - Estão isentas, exceto as operações ocasionais:",
            "1 - Estão isentas desde que exista autorização prévia:",
            "1 - Nunca estão isentas:",
            "1 - Estão isentas, unicamente se autorizadas:",
            "1 - Estão isentas, com exclusão das ocasionais:",
    })
    @DisplayName("Parecer R3-03: citar só a alínea sob enunciado com negação, excepção ou condição não é DEMONSTRATED; com o enunciado inteiro é")
    void itemAloneCannotDropTheChapeauContext(String chapeau) {
        assertThat(demonstrated(chapeau, ITEM)).isFalse();
        assertThat(demonstrated(chapeau, chapeau + " " + ITEM)).isTrue();
    }

    @Test
    @DisplayName("Parecer R3-03: enunciado neutro: a alínea sozinha continua aceite")
    void neutralChapeauKeepsWorking() {
        assertThat(demonstrated("1 - Estão isentas as operações:", ITEM)).isTrue();
    }

    @Test
    @DisplayName("Parecer R3-03: recorte que começa depois de «Não» é rejeitado no ramo contíguo")
    void cutAfterNegationIsRejected() {
        String chapeau = "1 - Não estão isentas as operações seguintes:";
        String evidence = "Artigo teste\n" + chapeau + "\n" + ITEM + "\n";
        String u = SupportVerifier.unitContext(ITEM, evidence);
        String c = SupportVerifier.contiguousUnit(ITEM, evidence);
        assertThat(gate.citationVerified("estão isentas as operações seguintes: " + ITEM, "x", evidence, u, c)).isFalse();
        assertThat(gate.citationVerified(chapeau + " " + ITEM, "x", evidence, u, c)).isTrue();
    }

    @Test
    @DisplayName("Parecer R3-03: recorte dentro de uma linha com excepção é rejeitado; a linha inteira é aceite")
    void cutInsideAQualifiedLine() {
        String line = "2 - As operações são isentas, salvo as ocasionais, nos termos gerais;";
        String evidence = "Artigo teste\n" + line + "\n";
        String anchor = "As operações são isentas";
        assertThat(gate.decide(supports("As operações são isentas"), "x", evidence, SupportVerifier.unitContext(anchor, evidence), "",
                SupportVerifier.normativeUnit(anchor, evidence), List.of()).demonstrated()).isFalse();
        assertThat(gate.decide(supports(line), "x", evidence, SupportVerifier.unitContext(anchor, evidence), "",
                SupportVerifier.normativeUnit(anchor, evidence), List.of()).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("Parecer R4-02: contexto coberto só pela âncora de uma condição da mesma linha; frase alheia ou marca dentro de palavra não servem")
    void contextCoveredOnlyByAConditionAnchorOnTheSameLine() {
        String line = "b) Nos casos que não sejam de montante fixo, só com documentos comunicados;";
        String evidence = "Artigo teste\n1 - Só podem ser realizadas:\n" + line + "\n";
        String anchor = "só com documentos comunicados";
        var legal = SupportVerifier.unitContext(anchor, evidence);
        var unit = SupportVerifier.normativeUnit(anchor, evidence);
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of("Nos casos que não sejam de montante fixo")).demonstrated()).isTrue();
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of("A norma não sejam abrangidas pelo pedido")).demonstrated()).isFalse();
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of("senão sejam de montante fixo")).demonstrated()).isFalse();
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of()).demonstrated()).isFalse();
    }

    @Test
    @DisplayName("Parecer R4-02: a linha de continuação com negação faz parte do contexto obrigatório")
    void continuationLineIsMandatoryContext() {
        String evidence = "Artigo teste\n1 - Estão isentas:\na) Operações médicas de todo o tipo;\nNão se aplica a privados.\nb) Outras;\n";
        String anchor = "a) Operações médicas de todo o tipo;";
        var legal = SupportVerifier.unitContext(anchor, evidence);
        var unit = SupportVerifier.normativeUnit(anchor, evidence);
        assertThat(unit.continuations()).containsExactly("Não se aplica a privados.");
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of()).demonstrated()).isFalse();
        assertThat(gate.decide(supports(anchor), "x", evidence, legal, "", unit, List.of("Não se aplica a privados")).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("Parecer R5-02: âncora de condição só com o conector não cobre a oração; com a oração inteira cobre")
    void conditionMustCoverTheWholeClause() {
        String chapeau = "1 - Não estão isentas as operações ocasionais:";
        String evidence = "Artigo teste\n" + chapeau + "\n" + ITEM + "\n";
        var legal = SupportVerifier.unitContext(ITEM, evidence);
        var unit = SupportVerifier.normativeUnit(ITEM, evidence);
        assertThat(gate.decide(supports(ITEM), "x", evidence, legal, "", unit, List.of("1 - Não estão isentas")).demonstrated()).isFalse();
        assertThat(gate.decide(supports(ITEM), "x", evidence, legal, "", unit, List.of("Não estão isentas as operações ocasionais")).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("Parecer R5-03: linha em branco não corta o contexto: unidade, barreira e texto integral")
    void blankLineDoesNotCutContext() {
        String evidence = "Artigo teste\n1 - Estão isentas:\na) Operações médicas de todo o tipo;\n\nNão se aplica a privados.\nb) Outras;\n";
        String anchor = "a) Operações médicas de todo o tipo;";
        var unit = SupportVerifier.normativeUnit(anchor, evidence);
        assertThat(unit.continuations()).containsExactly("Não se aplica a privados.");
        assertThat(unit.fullText(true)).contains("Não se aplica a privados.");
        assertThat(gate.decide(supports(anchor), "x", evidence, SupportVerifier.unitContext(anchor, evidence), "", unit, List.of()).demonstrated()).isFalse();
    }

    @Test
    @DisplayName("Parecer R4-02: marca dentro de palavra não conta como negação")
    void markerInsideAWordIsNotAMarker() {
        String evidence = "Artigo teste\n1 - As operações com salvoconduto especial são isentas nos termos gerais;\n";
        String anchor = "são isentas nos termos gerais";
        assertThat(gate.decide(supports(anchor), "x", evidence, SupportVerifier.unitContext(anchor, evidence), "",
                SupportVerifier.normativeUnit(anchor, evidence), List.of()).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("Unidade normativa completa: a excepção na mesma linha aparece no texto integral")
    void fullTextKeepsTheWholeLine() {
        String evidence = "Artigo teste\n1 - As prestações de serviços médicos são isentas, salvo as ocasionais;\n";
        var unit = SupportVerifier.normativeUnit("prestações de serviços médicos", evidence);
        assertThat(unit.fullText(false)).contains("salvo as ocasionais");
    }

    @Test
    @DisplayName("Parecer R3-02: ocorrência não identificável: janela que atravessa números, âncora repetida")
    void occurrenceNotIdentified() {
        String across = "Artigo teste\n1 - Regime anterior:\na) Operações antigas;\n2 - Estão isentas:\na) Operações médicas;\n";
        assertThat(SupportVerifier.occurrenceIdentified("a) Operações antigas; 2 - Estão isentas: a) Operações médicas;", across)).isFalse();
        String repeated = "Artigo teste\n1 - Não estão isentas:\na) Operações médicas; realizadas por privados;\n2 - Estão isentas:\na) Operações médicas; realizadas por públicos;\n";
        assertThat(SupportVerifier.occurrenceIdentified("a) Operações médicas;", repeated)).isFalse();
        assertThat(SupportVerifier.contiguousUnit("a) Operações médicas;", repeated)).isEmpty();
        assertThat(SupportVerifier.occurrenceIdentified("realizadas por públicos", repeated)).isTrue();
        // Parecer R4-03: verbas com parêntese, como no CIVA.
        String both = civa9.lines().filter(l -> l.startsWith("1) ") || l.startsWith("2) ")).collect(java.util.stream.Collectors.joining(" "));
        assertThat(SupportVerifier.occurrenceIdentified(both, civa9)).isFalse();
        assertThat(SupportVerifier.occurrenceIdentified(ANCHOR, civa9)).isTrue();
    }

    @Test
    @DisplayName("Uma citação verificada nunca dispensa o veredicto: NAO_SUPORTA continua não demonstrado")
    void citationNeverOverridesTheVerdict() {
        var assessment = new EntailmentValidator.Assessment(EntailmentValidator.Verdict.NAO_SUPORTA, java.util.Map.of(),
                java.util.Map.of(), "j", CHAPEAU + " 1) " + ANCHOR + ";", null, null);
        assertThat(gate.decide(assessment, STATEMENT, civa9, unit, contiguous, null, null).demonstrated()).isFalse();
    }
}
