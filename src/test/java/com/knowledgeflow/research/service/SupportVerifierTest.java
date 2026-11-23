package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.source.ConsolidatedArticleParser;
import com.knowledgeflow.research.source.LegislationReference;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suporte substantivo (KF-M02/CORR-03, F1/F2) com os textos oficiais e as proposições REAIS da
 * demonstração de 2026-10-23 (traces P1, P2 e A4).
 */
class SupportVerifierTest {

    static final String IRS = "IRS imposto sobre o rendimento das pessoas singulares";
    static final String IVA = "IVA imposto sobre o valor acrescentado";

    private final SupportVerifier support = new SupportVerifier();
    private final AnchorVerifier anchors = new AnchorVerifier();
    private final ConsolidatedArticleParser parser = new ConsolidatedArticleParser();

    private String content(String fixture, String code, int article) {
        var parsed = parser.parse(ResearchFixtures.page(fixture), new LegislationReference(code, article, null),
                LocalDate.of(2026, 10, 23));
        assertThat(parsed.confirmed()).isTrue();
        return parsed.content();
    }

    @Test
    @DisplayName("P1 real (falso positivo): âncora literal do 16.º (residência) NÃO sustenta 'não residentes excluídos das deduções'")
    void p1RealFalsePositiveRejected() {
        String art16 = content(ResearchFixtures.CIRS_16_REAL, "CIRS", 16);
        String statement = "Contribuintes não residentes em Portugal estão excluídos do regime de deduções à coleta do IRS aplicável a residentes";
        String anchor = "São residentes em território português as pessoas que, no ano a que respeitam os rendimentos: a) Hajam nele "
                + "permanecido mais de 183 dias, seguidos ou interpolados, em qualquer período de 12 meses com início ou fim no ano em "
                + "causa; b) Tendo permanecido por menos tempo, aí disponham, num qualquer dia do período referido na alínea anterior, "
                + "de habitação em condições que façam supor intenção atual de a manter e ocupar como residência habitual";

        assertThat(anchors.isAnchored(anchor, art16, statement)).as("presença literal").isTrue();
        SupportVerifier.Result r = support.check(statement, anchor, art16, "artigo 16.º do CIRS", IRS, List.of());
        assertThat(r.supported()).as("suporte substantivo; cobertura=%s em falta=%s", r.coverage(), r.uncoveredTerms()).isFalse();
        assertThat(r.uncoveredTerms()).contains("deduc", "colet", "exclu");
    }

    @Test
    @DisplayName("P2 real: âncora do CIVA 9.º 1) sustenta 'consultas de psicólogo isentas de IVA' (enunciado 'Estão isentas do imposto:')")
    void p2RealSupported() {
        String art9 = content(ResearchFixtures.CIVA_9, "CIVA", 9);
        String statement = "As prestações de serviços efetuadas por um psicólogo no exercício da profissão estão isentas de IVA";
        String anchor = "As prestações de serviços efetuadas no exercício das profissões de médico, odontologista, psicólogo, "
                + "parteiro, enfermeiro e outras profissões paramédicas";

        SupportVerifier.Result r = support.check(statement, anchor, art9, "artigo 9.º do CIVA", IVA, List.of());
        assertThat(r.supported()).as("cobertura=%s em falta=%s", r.coverage(), r.uncoveredTerms()).isTrue();
    }

    @Test
    @DisplayName("A4 real (CORR-11): '4 - (Revogado.)' é âncora literal pertinente, mas só sustenta a revogação actual — "
            + "não 'foi revogado e não se aplica'")
    void a4RealRevocationLimitedToCurrentState() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        String statement = "O n.º 4 do artigo 78.º do CIRS foi revogado e não se aplica";

        assertThat(anchors.isAnchored("4 - (Revogado.)", art78, statement)).isTrue();
        SupportVerifier.Result r = support.check(statement, "4 - (Revogado.)", art78, "artigo 78.º do CIRS", IRS, List.of());
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).singleElement().asString().contains("âmbito temporal excede a evidência");
        String current = "O n.º 4 do artigo 78.º do CIRS está revogado";
        assertThat(support.check(current, "4 - (Revogado.)", art78, "artigo 78.º do CIRS", IRS, List.of()).supported()).isTrue();
    }

    @Test
    @DisplayName("Marca de revogação nunca sustenta que a disposição se aplica")
    void revocationMarkerNeverSupportsApplicability() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        String statement = "O n.º 4 do artigo 78.º do CIRS aplica-se às deduções à coleta";
        assertThat(anchors.isAnchored("4 - (Revogado.)", art78, statement)).isTrue(); // literal, pertinente pelo número
        assertThat(support.check(statement, "4 - (Revogado.)", art78, "artigo 78.º do CIRS", IRS, List.of()).supported()).isFalse();
    }

    @Test
    @DisplayName("F2: âncora curta só se for linha completa com marcador de unidade cujo identificador consta da afirmação")
    void shortAnchorsOnlyAsWholeLegalUnits() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        // número errado na afirmação
        assertThat(anchors.isAnchored("4 - (Revogado.)", art78, "O n.º 5 do artigo 78.º foi revogado")).isFalse();
        // fragmento arbitrário curto
        assertThat(anchors.isAnchored("residentes", art78, "O n.º 4 trata de residentes")).isFalse();
        // parte de uma linha, não a linha completa
        assertThat(anchors.isAnchored("4 - (Revogado", art78, "O n.º 4 foi revogado")).isFalse();
        // alínea curta sintética
        String synthetic = "Epígrafe\n1 - Texto:\nc) (Revogada.)\nd) Outra alínea com texto suficiente.";
        assertThat(anchors.isAnchored("c) (Revogada.)", synthetic, "A alínea c) do n.º 1 foi revogada")).isTrue();
        assertThat(anchors.isAnchored("c) (Revogada.)", synthetic, "A alínea d) do n.º 1 foi revogada")).isFalse();
    }

    @Test
    @DisplayName("A cobertura não depende de listas de artigos: uma afirmação sem termos na unidade da âncora falha em qualquer código")
    void coverageIsGeneric() {
        String art9 = content(ResearchFixtures.CIVA_9, "CIVA", 9);
        String anchor = "As prestações de serviços efetuadas no exercício das profissões de médico, odontologista, psicólogo";
        assertThat(support.check("Os psicólogos estão sujeitos a retenção na fonte de IRS sobre honorários",
                anchor, art9, "artigo 9.º do CIVA", IVA, List.of()).supported()).isFalse();
    }

    // -------------------------------------------------------------------------
    // KF-M02/CORR-04 — inversões de sentido e condições omitidas (textos oficiais reais)
    // -------------------------------------------------------------------------

    static final String N5_ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";
    static final String P2_ANCHOR = "As prestações de serviços efetuadas no exercício das profissões de médico, odontologista, "
            + "psicólogo, parteiro, enfermeiro e outras profissões paramédicas";
    static final String N6B_ANCHOR = "constarem de documentos comunicados pelos emitentes à Autoridade Tributária e Aduaneira";

    private SupportVerifier.Result n5(String statement) {
        return support.check(statement, N5_ANCHOR, content(ResearchFixtures.CIRS_78, "CIRS", 78), "artigo 78.º do CIRS", IRS, List.of());
    }

    private SupportVerifier.Result p2(String statement) {
        return support.check(statement, P2_ANCHOR, content(ResearchFixtures.CIVA_9, "CIVA", 9), "artigo 9.º do CIVA", IVA, List.of());
    }

    @Test
    @DisplayName("Codex: 'aplicam-se a não residentes' contra 'aplicam-se apenas aos residentes' — cobertura lexical total, mas SEM suporte")
    void codexInversionRejected() {
        SupportVerifier.Result r = n5("As deduções previstas no n.º 1 aplicam-se a não residentes");
        assertThat(r.coverage()).as("a heurística lexical isolada aceitaria").isGreaterThanOrEqualTo(SupportVerifier.MIN_COVERAGE);
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).contains("polaridade invertida"));

        assertThat(n5("As deduções previstas no n.º 1 aplicam-se a não-residentes").supported()).isFalse();
        assertThat(n5("As deduções previstas no n.º 1 aplicam-se aos sujeitos passivos que não sejam residentes").supported()).isFalse();
    }

    @Test
    @DisplayName("Predicado negado: 'não estão isentas' contra 'Estão isentas do imposto' — sem suporte")
    void negatedPredicateRejected() {
        SupportVerifier.Result r = p2("As prestações de serviços efetuadas por psicólogos no exercício da profissão não estão isentas de IVA");
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).contains("polaridade invertida"));
    }

    @Test
    @DisplayName("Restrição de âmbito: omitir 'apenas' ou inventar 'só' — sem suporte")
    void scopeRestrictionRespected() {
        SupportVerifier.Result omitted = n5("As deduções previstas no n.º 1 aplicam-se aos sujeitos passivos residentes em território português");
        assertThat(omitted.supported()).isFalse();
        assertThat(omitted.reasons()).contains("restrição de âmbito da norma omitida");

        SupportVerifier.Result invented = p2("Só as prestações de serviços efetuadas por psicólogos no exercício da profissão estão isentas de IVA");
        assertThat(invented.supported()).isFalse();
        assertThat(invented.reasons()).contains("restrição de âmbito inexistente na norma");

        assertThat(n5("As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português")
                .supported()).as("restrição preservada").isTrue();
    }

    static final String N6A_ANCHOR = "Mediante a identificação fiscal dos dependentes, ascendentes, colaterais ou beneficiários";
    static final String N6B_CASES_ANCHOR = "Nos casos de deduções que não sejam de montante fixo";

    @Test
    @DisplayName("POST-REAL07: âmbito alargado — uma regra delimitada ao n.º 1 não sustenta uma afirmação sobre o artigo ou sobre "
            + "todas as deduções, mesmo com cobertura total e todas as condições")
    void scopeDelimitedByNumberMustBeKept() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        String n5 = "As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português.";
        // P1 da REAL-07 (trace preservado): «previstas no artigo 78.º» ancorada no n.º 5.
        SupportVerifier.Result p1 = support.check("As deduções à coleta previstas no artigo 78.º aplicam-se apenas aos sujeitos passivos "
                + "residentes em território português.", n5, art78, "artigo 78.º do CIRS", IRS, List.of());
        assertThat(p1.supported()).isFalse();
        assertThat(p1.coverage()).as("a cobertura lexical sozinha não detecta").isEqualTo(1.0);
        assertThat(p1.reasons()).containsExactly("âmbito da norma alargado: a norma refere-se ao n.º 1 e a afirmação não o delimita");
        // n.º 6: «as deduções» em vez de «as deduções referidas nas alíneas … do n.º 1».
        SupportVerifier.Result n6 = support.check("Nos casos de deduções que não sejam de montante fixo, as deduções só podem ser "
                + "realizadas se constarem de documentos comunicados à Autoridade Tributária e Aduaneira", N6B_ANCHOR, art78,
                "artigo 78.º do CIRS", IRS, List.of(N6A_ANCHOR, N6B_CASES_ANCHOR, N6B_ANCHOR));
        assertThat(n6.supported()).isFalse();
        assertThat(n6.reasons()).anyMatch(r -> r.startsWith("âmbito da norma alargado: a norma refere-se ao n.º 1"));
        // Outro número em vez do delimitado também não serve.
        assertThat(support.check("As deduções previstas no n.º 2 aplicam-se apenas aos sujeitos passivos residentes em território "
                + "português.", n5, art78, "artigo 78.º do CIRS", IRS, List.of()).supported()).isFalse();
        // Controlo: com a delimitação mantida, aceite.
        assertThat(support.check("As deduções previstas no n.º 1 do artigo 78.º do CIRS aplicam-se apenas aos sujeitos passivos "
                + "residentes em território português", n5, art78, "artigo 78.º do CIRS", IRS, List.of()).supported()).isTrue();
    }

    @Test
    @DisplayName("Condição omitida: norma com 'nos casos… só… se…' exige CADA condição estruturada; com todas, suporte directo aceite")
    void omittedConditionRejected() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        // POST-REAL07: a afirmação mantém o âmbito da norma (deduções das alíneas a) a i) e k) do n.º 1).
        String statement = "As deduções referidas nas alíneas a) a i) e na alínea k) do n.º 1, nos casos de deduções que não sejam de montante fixo, "
                + "só podem ser realizadas se constarem de documentos comunicados à Autoridade Tributária e Aduaneira";
        SupportVerifier.Result without = support.check(statement, N6B_ANCHOR, art78, "artigo 78.º do CIRS", IRS, List.of());
        assertThat(without.supported()).isFalse();
        assertThat(without.reasons()).contains("condição/excepção da norma sem condição estruturada");

        assertThat(support.check(statement, N6B_ANCHOR, art78, "artigo 78.º do CIRS", IRS,
                List.of(N6A_ANCHOR, N6B_CASES_ANCHOR, N6B_ANCHOR)).supported())
                .as("com todas as condições estruturadas e ancoradas (alínea a) cumulativa incluída)").isTrue();

        SupportVerifier.Result dropped = support.check("As deduções constam de documentos comunicados à Autoridade Tributária e Aduaneira",
                N6B_ANCHOR, art78, "artigo 78.º do CIRS", IRS, List.of(N6A_ANCHOR, N6B_CASES_ANCHOR, N6B_ANCHOR));
        assertThat(dropped.supported()).as("afirmação incondicional com restrição omitida").isFalse();
    }

    // -------------------------------------------------------------------------
    // KF-M02/ARCH-01 — reproduções do Codex (revisão da CORR-04), agora rejeitadas pela camada determinística
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Codex 1 (78.º n.º 6): uma condição ancorada já não desbloqueia a condição cumulativa omitida da alínea a)")
    void codexOneConditionNoLongerUnlocksOthers() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        String statement = "Nos casos de deduções que não sejam de montante fixo, as deduções referidas nas alíneas a) a i) e na "
                + "alínea k) do n.º 1 só podem ser realizadas se constarem de documentos comunicados pelos emitentes à "
                + "Autoridade Tributária e Aduaneira";
        // Em 63e7a70: supported=true, cobertura 1,0 (qualquer condição ancorada desbloqueava as outras).
        SupportVerifier.Result r = support.check(statement, N6B_ANCHOR, art78, "artigo 78.º do CIRS", IRS, List.of(N6B_ANCHOR));
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).startsWith("qualificação sem condição estruturada: «a) mediante"));
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).startsWith("qualificação sem condição estruturada: «nos casos"));
    }

    @Test
    @DisplayName("Codex 1 (16.º n.º 14): condições cumulativas — só a alínea a) estruturada não basta")
    void codexCumulativeConditionsRequired() {
        String art16 = content(ResearchFixtures.CIRS_16_REAL, "CIRS", 16);
        String anchor = "um sujeito passivo considera-se residente em território português durante a totalidade do ano no qual "
                + "perca a qualidade de residente";
        String statement = "Um sujeito passivo considera-se residente em território português durante a totalidade do ano no qual "
                + "perca a qualidade de residente quando permaneça em território português mais de 183 dias nesse ano";
        String a = "Permaneça em território português mais de 183 dias, seguidos ou interpolados, nesse ano";
        SupportVerifier.Result r = support.check(statement, anchor, art16, "artigo 16.º do CIRS", IRS, List.of(a));
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).startsWith("qualificação sem condição estruturada: «b) obtenha"));
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).contains("sem prejuizo do disposto no numero seguinte"));
    }

    @Test
    @DisplayName("Codex 2: negação a mais de três palavras do termo — a negação vale até ao fim da oração")
    void codexDistantNegationRejected() {
        // Em 63e7a70: supported=true, cobertura 0,8 (âmbito da negação limitado a 3 palavras).
        SupportVerifier.Result r = n5("As deduções previstas no n.º 1 não podem em qualquer circunstância ser aplicadas apenas aos "
                + "sujeitos passivos residentes em território português");
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).anySatisfy(x -> assertThat(x).contains("polaridade invertida"));
        assertThat(n5("Os sujeitos passivos residentes em território português não são os únicos a quem se aplicam apenas as "
                + "deduções previstas no n.º 1").supported()).isFalse();
    }

    @Test
    @DisplayName("Excepção inventada: 'salvo quando…' sem excepção na norma — sem suporte")
    void inventedExceptionRejected() {
        SupportVerifier.Result r = p2("As prestações de serviços efetuadas por psicólogos estão isentas de IVA, salvo quando prestadas a empresas");
        assertThat(r.supported()).isFalse();
        assertThat(r.reasons()).contains("condição/excepção inexistente na norma");
    }

    @Test
    @DisplayName("Âmbito da negação pára na pontuação; 'aplicam-se' não é condição")
    void negationScopeAndEncliticSe() {
        var pol = SupportVerifier.polarity("nao se aplica a taxa. os residentes deduzem");
        assertThat(pol.get("resid")).containsExactly(false);
        assertThat(pol.get("aplic")).containsExactly(true);
        // ARCH-01: sem limite de palavras dentro da oração
        assertThat(SupportVerifier.polarity("nao podem em qualquer circunstancia ser aplicadas, residentes").get("aplic")).containsExactly(true);
        assertThat(n5("As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português")
                .reasons()).doesNotContain("condição/excepção da norma sem condição estruturada");
    }

    // ---------------------------------------------------------------------------------------------
    // KF-M02/REAL-08, correcção C1: identificadores de diplomas não são números de âmbito do artigo
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("C1: «Diretiva n.º 2003/48/CE», «Lei n.º 2/2020», «Decreto-Lei», «Regulamento (UE)», «Portaria», «Despacho» não são números do artigo")
    void diplomaIdentifiersAreNotScopeNumbers() {
        assertThat(SupportVerifier.numberReferences("bem como as retencoes efetuadas ao abrigo do artigo 11.º da diretiva n.º 2003/48/ce"))
                .isEmpty();
        assertThat(SupportVerifier.numberReferences("feita na declaracao a que se refere o n.º 1 do artigo 57.º da lei n.º 2/2020"))
                .containsExactly("1");
        assertThat(SupportVerifier.numberReferences("decreto-lei n.º 41/2016 e o n.º 5")).containsExactly("5");
        assertThat(SupportVerifier.numberReferences("regulamento (ue) n.º 2016/679")).isEmpty();
        assertThat(SupportVerifier.numberReferences("portaria n.º 3, despacho n.º 4, decreto regulamentar n.º 5, lei organica n.º 6,"
                + " directiva n.º 7")).isEmpty();
        // números do artigo continuam, incluindo vários números e alíneas
        assertThat(SupportVerifier.numberReferences("as deducoes previstas no n.º 1 e no n.º 5")).containsExactly("1", "5");
        assertThat(SupportVerifier.numberReferences("na alinea c) do n.º 1")).containsExactly("1");
        // uma vírgula ou outra palavra separa a designação: o número volta a ser de âmbito (sem falsos negativos por proximidade)
        assertThat(SupportVerifier.numberReferences("nos termos da lei, o n.º 2")).containsExactly("2");
        assertThat(SupportVerifier.numberReferences("a lei aplica-se ao n.º 2")).containsExactly("2");
        assertThat(SupportVerifier.numberReferences("aleia n.º 3")).containsExactly("3");
    }

    @Test
    @DisplayName("C1: a regra do n.º 2 do 78.º («… Diretiva n.º 2003/48/CE …») deixa de ser rejeitada por «n.º 2003»; o âmbito real continua exigido")
    void n2RuleNoLongerRejectedByDirectiveNumber() {
        String art78 = content(ResearchFixtures.CIRS_78, "CIRS", 78);
        String anchor = "São ainda deduzidos à coleta os pagamentos por conta do imposto e as importâncias retidas na fonte que tenham "
                + "aquela natureza";
        SupportVerifier.Result n2 = support.check("São ainda deduzidos à coleta as importâncias retidas na fonte que tenham aquela "
                + "natureza", anchor, art78, "artigo 78.º do CIRS", IRS, List.of());
        assertThat(n2.reasons()).noneMatch(r -> r.contains("n.º 2003"));
        assertThat(n2.supported()).as("razões: %s", n2.reasons()).isTrue();
        // adversarial: a mesma frase com polaridade invertida continua rejeitada (por outra razão)
        assertThat(support.check("Não são deduzidos à coleta as importâncias retidas na fonte que tenham aquela natureza", anchor, art78,
                "artigo 78.º do CIRS", IRS, List.of()).supported()).isFalse();
        // adversarial: restrição de residência inventada sobre o n.º 2 continua rejeitada
        assertThat(support.check("São ainda deduzidos à coleta apenas aos residentes as importâncias retidas na fonte que tenham aquela "
                + "natureza", anchor, art78, "artigo 78.º do CIRS", IRS, List.of()).supported()).isFalse();
    }

    @Test
    @DisplayName("C1 sem falsos negativos: unidade com diploma E número real — omitir o número real continua a ser âmbito alargado")
    void realScopeNumberStillRequiredNextToDiploma() {
        String content = "Artigo 99.º\n1 - As deduções previstas no n.º 1 do artigo 57.º da Lei n.º 2/2020 aplicam-se apenas aos residentes.\n";
        String anchor = "As deduções previstas no n.º 1 do artigo 57.º da Lei n.º 2/2020 aplicam-se apenas aos residentes";
        SupportVerifier.Result omitted = support.check("As deduções aplicam-se apenas aos residentes", anchor, content,
                "artigo 99.º do CIRS", IRS, List.of());
        assertThat(omitted.supported()).isFalse();
        assertThat(omitted.reasons()).contains("âmbito da norma alargado: a norma refere-se ao n.º 1 e a afirmação não o delimita")
                .noneMatch(r -> r.contains("n.º 2 e"));
        SupportVerifier.Result kept = support.check("As deduções previstas no n.º 1 do artigo 57.º aplicam-se apenas aos residentes", anchor,
                content, "artigo 99.º do CIRS", IRS, List.of());
        assertThat(kept.reasons()).noneMatch(r -> r.startsWith("âmbito da norma alargado"));
    }

    @Test
    @DisplayName("C1 (parecer C1-01..04): parêntese arbitrário não é qualificativo de diploma; sem janela; espaço não separável; fronteira de palavra")
    void diplomaRecognitionIsExact() {
        // C1-01: «Lei (artigo 57.º) n.º 1» — o n.º 1 qualifica o artigo, não identifica um diploma
        assertThat(SupportVerifier.numberReferences("as deducoes previstas na lei (artigo 57.º) n.º 1 aplicam-se aos residentes"))
                .containsExactly("1");
        String content = "Artigo 99.º\n1 - As deduções previstas na Lei (artigo 57.º) n.º 1 aplicam-se aos residentes.\n";
        String anchor = "As deduções previstas na Lei (artigo 57.º) n.º 1 aplicam-se aos residentes.";
        SupportVerifier.Result omitted = support.check("As deduções aplicam-se aos residentes", anchor, content, "artigo 99.º do CIRS", IRS,
                List.of());
        assertThat(omitted.supported()).isFalse();
        assertThat(omitted.reasons()).contains("âmbito da norma alargado: a norma refere-se ao n.º 1 e a afirmação não o delimita");
        // qualificativos europeus fechados continuam reconhecidos
        assertThat(SupportVerifier.numberReferences("regulamento (ue, euratom) n.º 2020/2093 e diretiva (ue) n.º 2018/822")).isEmpty();
        // C1-02: sem janela — um parêntese longo não qualificativo nunca exclui; designação longa reconhecida a qualquer distância
        assertThat(SupportVerifier.numberReferences("decreto regulamentar (abcdefghijklmnopqrst) n.º 5")).containsExactly("5");
        assertThat(SupportVerifier.numberReferences("x".repeat(60) + " decreto regulamentar n.º 5")).isEmpty();
        // C1-03: espaço não separável entre a designação e «n.º»
        assertThat(SupportVerifier.numberReferences("lei\u00a0n.º 2/2020")).isEmpty();
        assertThat(SupportVerifier.numberReferences("decreto\u00a0regulamentar n.º 5")).isEmpty();
        // C1-04: fronteira de palavra independente da posição («alei» não é «lei»)
        assertThat(SupportVerifier.numberReferences("alei" + " ".repeat(37) + "n.º 1")).containsExactly("1");
        assertThat(SupportVerifier.numberReferences("a" + "x".repeat(36) + "lei n.º 1")).containsExactly("1");
    }
}
