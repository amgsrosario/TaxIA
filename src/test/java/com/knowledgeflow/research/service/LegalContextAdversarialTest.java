package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * KF-PILOT/R-1 a R-3: bateria adversarial reproduzível, sem modelo. A barreira simulada diz sempre SUPORTA, com citação curta ou com a
 * unidade inteira: nenhuma norma com negação, excepção ou condição pode passar sem o contexto na proposição. Reutiliza os 29 casos da
 * demonstração de 04/11, incluindo os sete que passavam, e acrescenta contra-exemplos.
 */
class LegalContextAdversarialTest {

    private static final String ITEM = "a) Prestações de serviços médicos em território nacional;";
    private static final String STATEMENT = "As prestações de serviços médicos em território nacional estão isentas de imposto";
    private static final String INLINE_ANCHOR = "prestações de serviços médicos em território nacional";

    record Case(String id, String evidence, String anchor, String statement) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static Case chapeau(String id, String chapeau) {
        return new Case(id, "Artigo 1.º\n1 - " + chapeau + ":\n" + ITEM + "\nb) Outras operações tipificadas;\n", ITEM, STATEMENT);
    }

    private static Case inline(String id, String line) {
        return new Case(id, "Artigo 1.º\n1 - " + line + ";\n2 - Outro número sem relação;\n", INLINE_ANCHOR, STATEMENT);
    }

    private static String resource(String name) {
        try (InputStream in = LegalContextAdversarialTest.class.getResourceAsStream("/kf-m02/real08/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Case> mustNotPass() {
        List<Case> c = new ArrayList<>(List.of(
                // Os sete que passavam em 04/11.
                chapeau("N4-enunciado", "Ficam fora da isenção as operações seguintes"),
                chapeau("N6-enunciado", "São tributadas as operações seguintes"),
                chapeau("E6-enunciado", "Estão isentas, fora dos casos ocasionais, as operações seguintes"),
                chapeau("C4-enunciado", "Estão isentas a menos que falte autorização as operações seguintes"),
                chapeau("C6-enunciado", "Estão isentas sempre que exista autorização as operações seguintes"),
                inline("E6-linha", "As prestações de serviços médicos em território nacional são isentas, fora dos casos ocasionais"),
                inline("C4-linha", "As prestações de serviços médicos em território nacional são isentas a menos que falte autorização"),
                // Os restantes da demonstração.
                chapeau("N1-enunciado", "Não estão isentas as operações seguintes"),
                chapeau("N2-enunciado", "Nunca estão isentas as operações seguintes"),
                chapeau("N3-enunciado", "Estão excluídas da isenção as operações seguintes"),
                chapeau("N5-enunciado", "Não beneficiam, em caso algum, da isenção as operações seguintes"),
                chapeau("E1-enunciado", "Estão isentas, salvo as ocasionais, as operações seguintes"),
                chapeau("E2-enunciado", "Estão isentas, exceto as ocasionais, as operações seguintes"),
                chapeau("E3-enunciado", "Estão isentas, com exceção das ocasionais, as operações seguintes"),
                chapeau("E4-enunciado", "Estão isentas, à exceção das ocasionais, as operações seguintes"),
                chapeau("E5-enunciado", "Estão isentas, ressalvadas as ocasionais, as operações seguintes"),
                chapeau("C1-enunciado", "Estão isentas desde que exista autorização prévia as operações seguintes"),
                chapeau("C9-enunciado", "Estão isentas sem prejuízo do disposto no número seguinte as operações seguintes"),
                inline("N1-linha", "As prestações de serviços médicos em território nacional não estão isentas"),
                inline("E1-linha", "As prestações de serviços médicos em território nacional são isentas, salvo as ocasionais"),
                // Contra-exemplos novos: enunciado de sentido contrário sem marca, sujeição, contexto só na continuação.
                chapeau("X1-sem-marca", "Pagam imposto à taxa normal as operações seguintes"),
                chapeau("X2-sujeicao", "Estão isentas e sujeitas a autorização as operações seguintes"),
                new Case("X3-continuacao", "Artigo 1.º\n1 - Estão isentas:\n" + ITEM + "\n\nNão se aplica a privados.\nb) Outras;\n", ITEM, STATEMENT),
                chapeau("X4-virgula", "Não estão isentas, em caso algum, as operações ocasionais"),
                chapeau("X5-sem-marca", "Ficam abrangidas pelo imposto, à taxa reduzida, as operações seguintes")));
        return c.stream();
    }

    /** Os restantes 9 dos 29 casos de 04/11: condições sem marca no léxico e os dois textos reais, que têm de continuar recusados. */
    static Stream<Case> refusedByAnyDeterministicLayer() {
        String cirs = resource("cirs-78-real07-content.txt");
        String n5 = "aplicam-se apenas aos sujeitos passivos residentes em território português";
        String n6b = "constarem de documentos comunicados pelos emitentes à Autoridade Tributária e Aduaneira";
        return Stream.of(
                chapeau("C2-enunciado", "Estão isentas se existir autorização prévia as operações seguintes"),
                chapeau("C3-enunciado", "Estão isentas quando exista autorização prévia as operações seguintes"),
                chapeau("C5-enunciado", "Estão isentas caso exista autorização as operações seguintes"),
                chapeau("C7-enunciado", "Estão isentas, mediante autorização prévia, as operações seguintes"),
                chapeau("C8-enunciado", "Só estão isentas, com autorização prévia, as operações seguintes"),
                inline("C2-linha", "As prestações de serviços médicos em território nacional são isentas se existir autorização"),
                inline("C7-linha", "As prestações de serviços médicos em território nacional são isentas mediante autorização"),
                new Case("REAL-1-generaliza-n5", cirs, n5, "As deduções à coleta previstas no artigo 78.º aplicam-se apenas aos sujeitos passivos residentes em território português"),
                new Case("REAL-3-n6b-sem-condicao", cirs, n6b, "As deduções só podem ser realizadas se constarem de documentos comunicados pelos emitentes à Autoridade Tributária e Aduaneira"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedByAnyDeterministicLayer")
    @DisplayName("Os restantes casos da demonstração continuam recusados por uma camada determinística, antes de qualquer modelo")
    void remainingCasesStayRefused(Case c) {
        boolean supported = new SupportVerifier().check(c.statement(), c.anchor(), c.evidence(), "artigo 1.º do CIVA",
                "IVA imposto sobre o valor acrescentado", List.of()).supported();
        boolean guardRefuses = NormativeContextGuard.violation(c.statement(), List.of(), SupportVerifier.normativeUnit(c.anchor(), c.evidence())).isPresent();
        assertThat(!supported || guardRefuses).as(c.id()).isTrue();
    }

    private static Map<EntailmentValidator.Dimension, EntailmentValidator.DimensionStatus> allConform() {
        Map<EntailmentValidator.Dimension, EntailmentValidator.DimensionStatus> d = new EnumMap<>(EntailmentValidator.Dimension.class);
        for (var x : EntailmentValidator.Dimension.values()) d.put(x, EntailmentValidator.DimensionStatus.CONFORME);
        return d;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustNotPass")
    @DisplayName("R-2/R-3: sem o contexto na proposição, a guarda recusa, mesmo que a citação traga a unidade inteira")
    void contextNotPreservedIsRefused(Case c) {
        assertThat(NormativeContextGuard.violation(c.statement(), List.of(), SupportVerifier.normativeUnit(c.anchor(), c.evidence())))
                .as(c.id()).isPresent();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustNotPass")
    @DisplayName("R-2: citar só a âncora também é recusado na barreira, salvo enunciados sem marca, que dependem da guarda")
    void shortCitationStaysRefusedOrGuarded(Case c) {
        var assessment = new EntailmentValidator.Assessment(EntailmentValidator.Verdict.SUPORTA, allConform(), Map.of(), "j", c.anchor(), null, null);
        boolean demonstrated = new EntailmentGate().decide(assessment, c.statement(), c.evidence(), SupportVerifier.unitContext(c.anchor(), c.evidence()),
                SupportVerifier.contiguousUnit(c.anchor(), c.evidence()), SupportVerifier.normativeUnit(c.anchor(), c.evidence()), List.of()).demonstrated();
        if (!c.id().endsWith("sem-marca")) assertThat(demonstrated).as(c.id()).isFalse();
    }

    @Test
    @DisplayName("Controlos: normas neutras, n.º 5 correcto e verba 1) passam a guarda")
    void controlsPass() {
        String cirs = resource("cirs-78-real07-content.txt");
        String civa = resource("civa-9-real07-content.txt");
        String n5 = "aplicam-se apenas aos sujeitos passivos residentes em território português";
        String verba = "As prestações de serviços efetuadas no exercício das profissões de médico, odontologista, psicólogo, parteiro, enfermeiro e outras profissões paramédicas";
        assertThat(NormativeContextGuard.violation(STATEMENT, List.of(), SupportVerifier.normativeUnit(ITEM, chapeau("x", "Estão isentas as operações seguintes").evidence()))).isEmpty();
        assertThat(NormativeContextGuard.violation("As deduções previstas no n.º 1 do artigo 78.º aplicam-se apenas aos sujeitos passivos residentes em território português",
                List.of(), SupportVerifier.normativeUnit(n5, cirs))).isEmpty();
        assertThat(NormativeContextGuard.violation("As prestações de serviços efetuadas por um psicólogo no exercício da profissão estão isentas de IVA",
                List.of(), SupportVerifier.normativeUnit(verba, civa))).isEmpty();
    }

    @Test
    @DisplayName("Contexto preservado na afirmação ou em condição estruturada passa; só o conector ou palavra coincidente não")
    void preservedContextPasses() {
        Case c = chapeau("x", "Estão isentas, salvo as operações ocasionais, as operações seguintes");
        var unit = SupportVerifier.normativeUnit(c.anchor(), c.evidence());
        assertThat(NormativeContextGuard.violation(STATEMENT + ", salvo as operações ocasionais", List.of(), unit)).isEmpty();
        assertThat(NormativeContextGuard.violation(STATEMENT, List.of("salvo as operações ocasionais"), unit)).isEmpty();
        assertThat(NormativeContextGuard.violation(STATEMENT + ", salvo outras coisas", List.of(), unit)).isPresent();
        assertThat(NormativeContextGuard.violation(STATEMENT + ", salvo as operações gerais", List.of(), unit)).as("só metade da oração").isPresent();
        assertThat(NormativeContextGuard.violation(STATEMENT, List.of("salvo"), unit)).isPresent();
        assertThat(NormativeContextGuard.violation(STATEMENT + ", ressalvo", List.of(), unit)).isPresent();
    }

    @Test
    @DisplayName("«Sujeitos passivos» não é sujeição: não conta como marca")
    void sujeitosPassivosIsNotAMarker() {
        assertThat(LegalContextLexicon.MARKER.matcher("aplicam-se aos sujeitos passivos residentes").find()).isFalse();
        assertThat(LegalContextLexicon.MARKER.matcher("ficam sujeitas a autorização").find()).isTrue();
    }

    @Test
    @DisplayName("R-1: o texto oficial traz o enunciado, a excepção da mesma linha e as continuações, e é citação literal")
    void officialTextKeepsTheWholeUnit() {
        String adjacent = "Artigo 1.º\n1 - Não estão isentas:\n" + ITEM + "\n\nSalvo as ocasionais.\nb) Outras;\n";
        assertThat(SupportVerifier.officialText(ITEM, adjacent)).isEqualTo("1 - Não estão isentas: " + ITEM + " Salvo as ocasionais.");
        String sameLine = "Artigo 1.º\n1 - As prestações de serviços médicos em território nacional são isentas, salvo as ocasionais;\n";
        assertThat(SupportVerifier.officialText(INLINE_ANCHOR, sameLine)).contains("salvo as ocasionais");
        String apart = "Artigo 1.º\n1 - Não estão isentas:\na) Primeira;\n" + "b) Prestações de serviços médicos em território nacional;\n";
        String text = SupportVerifier.officialText("b) Prestações de serviços médicos em território nacional;", apart);
        assertThat(text).startsWith("1 - Não estão isentas:» […] «b) Prestações");
        for (String quote : text.split("» \\[…\\] «")) assertThat(apart).contains(quote);
    }

    // ===== Parecer R123: F2, F3, F4 e F5 =====

    private static final String CIVA = resource("civa-9-real07-content.txt");
    private static final String TOP = "Estão isentas do imposto:";

    private static String civaLine(String prefix) {
        return CIVA.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow().strip();
    }

    @Test
    @DisplayName("F2: cada verba do CIVA é regida pelo enunciado do artigo, e nunca por outra verba terminada em dois pontos")
    void civaVerbasKeepTheirOwnChapeau() {
        for (String verba : List.of("24)", "30)", "36)", "38)")) {
            SupportVerifier.NormativeUnit unit = SupportVerifier.normativeUnit(civaLine(verba), CIVA);
            assertThat(unit.chapeau()).as(verba).isEqualTo(TOP);
            assertThat(unit.outer()).as(verba).isEmpty();
            assertThat(unit.continuations()).as(verba).isEmpty();
        }
        assertThat(SupportVerifier.officialText(civaLine("30)"), CIVA)).isEqualTo(
                "Estão isentas do imposto:» […] «30) As operações sujeitas a imposto municipal sobre as transmissões onerosas de imóveis;");
        assertThat(SupportVerifier.officialText(civaLine("36)"), CIVA)).isEqualTo(
                "Estão isentas do imposto:» […] «36) Os serviços de alimentação e bebidas fornecidos pelas entidades patronais aos seus empregados;");
    }

    @Test
    @DisplayName("F2: uma alínea é regida pela sua verba e, acima dela, pelo enunciado do artigo")
    void civaItemKeepsTheWholeHierarchy() {
        SupportVerifier.NormativeUnit excluded = SupportVerifier.normativeUnit("b) A locação de áreas para recolha ou estacionamento colectivo de veículos;", CIVA);
        assertThat(excluded.chapeau()).isEqualTo("29) A locação de bens imóveis. Esta isenção não abrange:");
        assertThat(excluded.outer()).containsExactly(TOP);
        assertThat(NormativeContextGuard.violation("A locação de áreas para recolha ou estacionamento colectivo de veículos está isenta de imposto",
                List.of(), excluded)).as("excluída pela verba 29").isPresent();
        SupportVerifier.NormativeUnit music = SupportVerifier.normativeUnit("a) Cedência de bandas de música;", CIVA);
        assertThat(music.chapeau()).startsWith("35) ");
        assertThat(music.outer()).containsExactly(TOP);
    }

    @Test
    @DisplayName("F2: os controlos reais do CIVA, com o contexto na proposição, passam a verificação")
    void civaPositiveSample() {
        Map<String, String> sample = Map.of(
                "38)", "As prestações de serviços efetuadas por intérprete de língua gestual portuguesa estão isentas de imposto",
                "36)", "Os serviços de alimentação e bebidas fornecidos pelas entidades patronais aos seus empregados estão isentos de imposto",
                "30)", "As operações sujeitas a imposto municipal sobre as transmissões onerosas de imóveis estão isentas de imposto",
                "24)", "As transmissões, pelo seu valor facial, de selos do correio em circulação ou de valores selados, e bem assim as respectivas comissões de venda estão isentas de imposto");
        sample.forEach((verba, statement) -> assertThat(NormativeContextGuard.violation(statement, List.of(),
                SupportVerifier.normativeUnit(civaLine(verba), CIVA))).as(verba).isEmpty());
    }

    @Test
    @DisplayName("F3: condição invertida e valor alterado não cobrem a oração da norma")
    void invertedConditionOrChangedValueIsRefused() {
        SupportVerifier.NormativeUnit inverted = new SupportVerifier.NormativeUnit("", List.of("São isentas a menos que falte autorização;"), List.of());
        assertThat(NormativeContextGuard.violation("Estas operações são isentas a menos que exista autorização", List.of(), inverted)).isPresent();
        assertThat(NormativeContextGuard.violation("Estas operações são isentas a menos que falte autorização", List.of(), inverted)).isEmpty();
        SupportVerifier.NormativeUnit value = new SupportVerifier.NormativeUnit("", List.of("São isentas, salvo se exceda 1.000 euros;"), List.of());
        assertThat(NormativeContextGuard.violation("São isentas, salvo se exceda 1 euro", List.of(), value)).isPresent();
        assertThat(NormativeContextGuard.violation("São isentas, salvo se exceda 1.000 euros", List.of(), value)).isEmpty();
        SupportVerifier.NormativeUnit noMarker = new SupportVerifier.NormativeUnit("", List.of("A taxa é de 23 %;"), List.of());
        assertThat(NormativeContextGuard.violation("A taxa é de 13 %", List.of(), noMarker)).as("valor sem marca").isPresent();
    }

    @Test
    @DisplayName("F4: condição temporal e efeito oposto no enunciado são recusados")
    void temporalConditionAndOppositeEffectAreRefused() {
        SupportVerifier.NormativeUnit after = new SupportVerifier.NormativeUnit("", List.of("São isentas após autorização;"), List.of());
        assertThat(NormativeContextGuard.violation("Estas operações são isentas", List.of(), after)).isPresent();
        SupportVerifier.NormativeUnit pay = new SupportVerifier.NormativeUnit("Pagam imposto as prestações de serviços médicos em território nacional seguintes:", List.of(ITEM), List.of());
        assertThat(NormativeContextGuard.violation(STATEMENT, List.of(), pay)).hasValueSatisfying(r -> assertThat(r).contains("efeito fiscal"));
    }

    @Test
    @DisplayName("F5: a continuação não absorve artigo ou epígrafe, mas mantém as linhas da mesma frase")
    void continuationStopsAtUnitBoundary() {
        String text = "1 - Primeira regra sem relação\ncontinuação quebrada da mesma frase;\nExcepto nos casos previstos adiante.\nEpígrafe do artigo seguinte\nArtigo 2.º\nOutro texto.\n";
        assertThat(SupportVerifier.normativeUnit("1 - Primeira regra sem relação", text).continuations())
                .containsExactly("continuação quebrada da mesma frase;", "Excepto nos casos previstos adiante.");
        assertThat(SupportVerifier.normativeUnit("Artigo 2.º", "Artigo 1.º\nTexto final.\nArtigo 2.º\nCapítulo II\n").continuations()).isEmpty();
    }

    @Test
    @DisplayName("F6: a recusa conservadora é reconhecida, e a indisponibilidade da barreira não")
    void deterministicRefusalIsNotATechnicalFailure() {
        assertThat(NormativeContextGuard.violation(STATEMENT, List.of(), new SupportVerifier.NormativeUnit("", List.of("Não são isentas;"), List.of())))
                .hasValueSatisfying(r -> assertThat(NormativeContextGuard.isDeterministicRefusal(r)).isTrue());
        assertThat(NormativeContextGuard.isDeterministicRefusal("segunda barreira de implicação indisponível")).isFalse();
        assertThat(NormativeContextGuard.isDeterministicRefusal(null)).isFalse();
    }

    @Test
    @DisplayName("F2: continuação de uma verba terminada em dois pontos não rege a verba seguinte")
    void verbaContinuationIsNotTheChapeauOfTheNextVerba() {
        String text = "Estão isentas do imposto:\n1) Primeira operação;\n2) Segunda operação, nos termos\nseguintes:\n3) Terceira operação;\n";
        assertThat(SupportVerifier.normativeUnit("3) Terceira operação;", text).chapeau()).isEqualTo("Estão isentas do imposto:");
    }

    @Test
    @DisplayName("F3: o separador de milhares não corta a oração, mesmo sem unidade no valor")
    void thousandsSeparatorDoesNotEndTheClause() {
        SupportVerifier.NormativeUnit value = new SupportVerifier.NormativeUnit("", List.of("São isentas, salvo se exceda 1.000 euros;"), List.of());
        assertThat(NormativeContextGuard.violation("São isentas, salvo se exceda 1.200", List.of(), value)).isPresent();
    }

    @Test
    @DisplayName("F5: cabeçalho com pontuação final pára a unidade, e uma linha quebrada sem pontuação continua-a")
    void headingWithPeriodAndWrappedLine() {
        assertThat(SupportVerifier.normativeUnit("1 - Regra sem relação", "1 - Regra sem relação\nArtigo 2.º - Regime especial.\nOutro texto.\n").continuations()).isEmpty();
        assertThat(SupportVerifier.normativeUnit("1 - Regra sem relação", "1 - Regra sem relação\nquebrada ao meio\nda frase, salvo excepção;\n2 - Outra.\n").continuations())
                .containsExactly("quebrada ao meio", "da frase, salvo excepção;");
    }

    // ===== Parecer R123 R2: A, B e C =====

    @Test
    @DisplayName("R2-A: ressalva repartida por linhas, começada por maiúscula, é apresentada e impede a afirmação incondicional")
    void splitReservationIsKept() {
        String anchor = "1 - As prestações de serviços médicos em território nacional são isentas;";
        String text = "Artigo 1.º\n" + anchor + "\nSalvo as prestações ocasionais realizadas\npor entidades sem autorização.\n2 - Outra regra;\n";
        assertThat(SupportVerifier.officialText(anchor, text)).isEqualTo(anchor + " Salvo as prestações ocasionais realizadas por entidades sem autorização.");
        assertThat(NormativeContextGuard.violation("As prestações de serviços médicos em território nacional são isentas", List.of(),
                SupportVerifier.normativeUnit(anchor, text))).isPresent();
    }

    @Test
    @DisplayName("R2-A: ressalva que abre uma enumeração fica na unidade; enumeração sem marca não")
    void reservationOpeningAnEnumeration() {
        assertThat(SupportVerifier.normativeUnit("1 - Regra", "1 - Regra\nSalvo nos casos seguintes:\na) Um;\n").continuations()).containsExactly("Salvo nos casos seguintes:");
        assertThat(SupportVerifier.normativeUnit("1 - Regra", "1 - Regra\nOs casos seguintes:\na) Um;\n").continuations()).isEmpty();
    }

    @Test
    @DisplayName("R2-B: valores comparados completos, com a sua unidade")
    void valuesAreComparedWhole() {
        SupportVerifier.NormativeUnit unit = new SupportVerifier.NormativeUnit("", List.of("As prestações têm o valor de 15 euros e 1.000 dias;"), List.of());
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 5 euros", List.of(), unit)).as("15→5").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 000 dias", List.of(), unit)).as("1.000→000").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 dias", List.of(), unit)).as("unidade trocada").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 5", List.of(), unit)).as("sem unidade").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 euros", List.of(), unit)).isEmpty();
        assertThat(NormativeContextGuard.violation("As prestações do artigo 9.º e do n.º 5 têm o valor de 15 euros", List.of(), unit)).as("identificadores").isEmpty();
    }

    @Test
    @DisplayName("R2-C: o enunciado de outro artigo não rege a verba, e a verba sem enunciado não é verificável")
    void chapeauDoesNotCrossArticles() {
        String anchor = "1) Serviços hospitalares;";
        String text = "Artigo 1.º\nEstão isentas do imposto:\n1) Consultas médicas;\nArtigo 2.º\nOperações tributáveis\n" + anchor + "\n";
        SupportVerifier.NormativeUnit unit = SupportVerifier.normativeUnit(anchor, text);
        assertThat(unit.chapeau()).isEmpty();
        assertThat(NormativeContextGuard.violation("Os serviços hospitalares estão isentos", List.of(), unit))
                .hasValueSatisfying(r -> assertThat(r).contains("enunciado da norma não identificado"));
    }

    // ===== Parecer R123 R3: N1 e N2 =====

    @Test
    @DisplayName("R3-N1: o sinal faz parte do valor")
    void signIsPartOfTheValue() {
        SupportVerifier.NormativeUnit positive = new SupportVerifier.NormativeUnit("", List.of("1 - As prestações têm o valor de 15 euros;"), List.of());
        SupportVerifier.NormativeUnit negative = new SupportVerifier.NormativeUnit("", List.of("1 - As prestações têm o valor de −15 euros;"), List.of());
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de -15 euros", List.of(), positive)).as("positivo→negativo").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 euros", List.of(), negative)).as("negativo→positivo").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de -15", List.of(), positive)).as("sem unidade").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de -15 euros", List.of(), negative)).as("sinais equivalentes").isEmpty();
        SupportVerifier.NormativeUnit mixed = new SupportVerifier.NormativeUnit("", List.of("1 - Valor de 15 euros e prazo de -15 dias;"), List.of());
        assertThat(NormativeContextGuard.violation("O valor é de -15 euros", List.of(), mixed)).as("sinal associado à unidade").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 euros", List.of(), positive)).isEmpty();
    }

    @Test
    @DisplayName("R3-N2: a oração repartida por linhas é comparada inteira, depois da quebra")
    void clauseSplitAcrossLinesIsComparedWhole() {
        String anchor = "1 - As prestações de serviços médicos em território nacional são isentas;";
        String text = "Artigo 1.º\n" + anchor + "\nSalvo as prestações ocasionais realizadas\npor entidades nacionais.\n2 - Outra regra;\n";
        SupportVerifier.NormativeUnit unit = SupportVerifier.normativeUnit(anchor, text);
        String base = "As prestações de serviços médicos em território nacional são isentas, salvo as prestações ocasionais realizadas";
        assertThat(NormativeContextGuard.violation(base + " por entidades estrangeiras", List.of(), unit)).as("alterada").isPresent();
        assertThat(NormativeContextGuard.violation(base, List.of(), unit)).as("omitida").isPresent();
        assertThat(NormativeContextGuard.violation(base + " por entidades nacionais", List.of(), unit)).as("preservada").isEmpty();
    }

    // ===== Parecer R123 R4: N3 =====

    @Test
    @DisplayName("R4-N3: sinal separado da magnitude, por espaço ou quebra de linha, não se perde")
    void signSeparatedFromTheMagnitude() {
        SupportVerifier.NormativeUnit positive = new SupportVerifier.NormativeUnit("", List.of("1 - As prestações têm o valor de 15 euros;"), List.of());
        SupportVerifier.NormativeUnit splitNegative = new SupportVerifier.NormativeUnit("", List.of("1 - As prestações têm o valor de −", "15 euros;"), List.of());
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de − 15 euros", List.of(), positive)).as("positivo→negativo").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de - 15", List.of(), positive)).as("sem unidade").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 euros", List.of(), splitNegative)).as("negativo→positivo").isPresent();
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de -15 euros", List.of(), splitNegative)).as("preservado").isEmpty();
    }

    @Test
    @DisplayName("R4-N3: controlo: o traço depois de um algarismo não é sinal")
    void dashAfterADigitIsNotASign() {
        SupportVerifier.NormativeUnit numbered = new SupportVerifier.NormativeUnit("", List.of("1 - 15 euros de valor;"), List.of());
        assertThat(NormativeContextGuard.violation("O valor é de 15 euros", List.of(), numbered)).isEmpty();
        SupportVerifier.NormativeUnit range = new SupportVerifier.NormativeUnit("", List.of("O prazo é de 10 - 15 dias;"), List.of());
        assertThat(NormativeContextGuard.violation("O prazo é de 15 dias", List.of(), range)).isEmpty();
    }

    // ===== Parecer R123 R5: N1 =====

    private static final List<String> SIGNS = List.of("-", "\u2212", "\u2013", "\u2014", "\u2010", "\u2011", "\uFE63", "\uFF0D");
    private static final List<String> GAPS = List.of("", " ", "\u00A0", "\u202F", "\u2009", "\u2003", "\u200B", "\n");

    private static Optional<String> valueCheck(String claim, String source) {
        return NormativeContextGuard.violation("As prestações têm o valor de " + claim, List.of(),
                new SupportVerifier.NormativeUnit("", List.of("1 - As prestações têm o valor de " + source + ";"), List.of()));
    }

    @Test
    @DisplayName("R5-N1: nenhum sinal se perde, com qualquer traço e qualquer espaço, nos dois sentidos")
    void noSignIsLostWithAnyDashOrSpace() {
        for (String sign : SIGNS) {
            for (String gap : GAPS) {
                String negative = sign + gap + "15 euros";
                assertThat(valueCheck(negative, "15 euros")).as("positivo→negativo U+%04X, «%s»", (int) sign.charAt(0), gap).isPresent();
                assertThat(valueCheck("15 euros", negative)).as("negativo→positivo U+%04X, «%s»", (int) sign.charAt(0), gap).isPresent();
                assertThat(valueCheck(negative, "-15 euros")).as("igual U+%04X, «%s»", (int) sign.charAt(0), gap).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("R5-N1: algarismos fora de 0 a 9 são recusados")
    void numeralRepresentations() {
        assertThat(valueCheck("\u0661\u0665 euros", "15 euros")).isPresent();
        assertThat(valueCheck("\uFF11\uFF15 euros", "15 euros")).as("largura total").isPresent();
        assertThat(valueCheck("15\u202Feuros", "15 euros")).isEmpty();
    }

    // ===== Parecer R123 R6: N1 e N2 =====

    @Test
    @DisplayName("R6-N1: carácter fora da lista fechada entre sinal e número leva à recusa, nos dois sentidos")
    void unsupportedCharacterIsRefused() {
        for (String gap : List.of("\u0085", "\u034F", "\u2060", "\u1680", "\u180E", "\u00AD", "\u0007")) {
            String negative = "\u2212" + gap + "15 euros";
            assertThat(valueCheck("15 euros", negative)).as("negativo→positivo U+%04X", (int) gap.charAt(0)).isPresent();
            assertThat(valueCheck(negative, "15 euros")).as("positivo→negativo U+%04X", (int) gap.charAt(0)).isPresent();
        }
    }

    @Test
    @DisplayName("R6-N2: expoente não colapsa em algarismo contíguo, mesmo com o número noutra unidade")
    void exponentDoesNotCollapse() {
        String anchor = "1 - As prestações têm o valor de 15² euros;";
        String text = "Artigo 1.º\n" + anchor + "\n2 - O prazo é de 152 dias;\n";
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 152 euros", List.of(), SupportVerifier.normativeUnit(anchor, text))).isPresent();
        assertThat(valueCheck("15¼ euros", "15 euros")).as("fracção").isPresent();
    }

    @Test
    @DisplayName("R6: controlos: texto legal corrente e espaços tipográficos não são recusados pela lista fechada")
    void ordinaryLegalTextIsSupported() {
        assertThat(valueCheck("15\u2003euros (art. 9.º, n.º 5, al. a))", "15 euros (art. 9.º, n.º 5, al. a))")).isEmpty();
        assertThat(valueCheck("15\u00A0euros", "«15 euros» - § único")).isEmpty();
    }

    @Test
    @DisplayName("R6: contra-exemplos exactos na unidade localizada, nos dois sentidos, com o número noutra unidade normativa")
    void exactCounterExamplesWithCollisionInAnotherUnit() {
        for (String gap : List.of("\u0085", "\u034F")) {
            String negative = "1 - As prestações têm o valor de \u2212" + gap + "15 euros;";
            String positive = "1 - As prestações têm o valor de 15 euros;";
            String other = "\n2 - O prazo é de -15 euros e de 15 euros;\n";
            assertThat(NormativeContextGuard.violation("As prestações têm o valor de 15 euros", List.of(),
                    SupportVerifier.normativeUnit(negative, "Artigo 1.º\n" + negative + other))).as("negativo→positivo U+%04X", (int) gap.charAt(0)).isPresent();
            assertThat(NormativeContextGuard.violation("As prestações têm o valor de \u2212" + gap + "15 euros", List.of(),
                    SupportVerifier.normativeUnit(positive, "Artigo 1.º\n" + positive + other))).as("positivo→negativo U+%04X", (int) gap.charAt(0)).isPresent();
        }
        String anchor = "1 - As prestações têm o valor de 15 euros;";
        assertThat(NormativeContextGuard.violation("As prestações têm o valor de 152 euros", List.of(),
                SupportVerifier.normativeUnit(anchor, "Artigo 1.º\n" + anchor + "\n2 - O valor é de 152 euros;\n"))).as("valor doutra unidade").isPresent();
    }
}
