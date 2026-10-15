package com.knowledgeflow.ai.grounding.scope;

import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ACTIVIDADE_REGULAR;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ACTO_ISOLADO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ADQUIRENTE_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.FORNECEDOR_NAO_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.SUBLOCACAO;
import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Marcadores do C9-A (ADR-009): acto isolado, operação entre não residentes e sublocação. As
 * perguntas vêm das medições do piloto em 2026-10-14 (held-out): as específicas têm de mostrar o
 * contexto; as genéricas e as do caso materialmente diferente não podem mostrá-lo.
 */
class RequiredContextMarkerDetectorTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();
    private final ApplicabilityMarkerDetector detector = new ApplicabilityMarkerDetector();

    /** Marcadores afirmados: o que conta para o contexto obrigatório (ADR-009). */
    private Set<ApplicabilityMarker> detect(String question) {
        return detector.detectAffirmed(question, classifier.classify(question));
    }

    /** Presença simples: o que conta para as exclusões (na dúvida exclui). */
    private Set<ApplicabilityMarker> present(String question) {
        return detector.detect(question, classifier.classify(question));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Quem realiza um acto isolado pode deduzir o IVA suportado nas despesas relacionadas com essa operação?",
            "Fiz uma operação isolada sujeita a IVA. Posso recuperar o IVA das compras ligadas a essa operação?",
            "Passei um ato isolado nas Finanças. Posso recuperar o IVA?",
            "Pratiquei uma só operação tributável; posso deduzir o IVA?",
            "Atos isolados: há direito à dedução?"})
    void actoIsoladoDetected(String question) {
        assertThat(detect(question)).contains(ACTO_ISOLADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Que IVA posso deduzir?",
            "Que IVA posso deduzir na minha actividade?",
            "Em que condições posso deduzir o IVA suportado?",
            "Tenho actividade aberta. Posso deduzir o IVA das despesas relacionadas com a minha actividade?",
            "Não é um acto isolado: tenho actividade regular. Posso deduzir o IVA?",
            "Não se trata de uma operação isolada; que IVA posso deduzir?"})
    void actoIsoladoNotDetected(String question) {
        assertThat(detect(question)).doesNotContain(ACTO_ISOLADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Aplica-se a inversão do sujeito passivo quando fornecedor e adquirente são ambos não residentes e a operação se localiza em Portugal?",
            "Uma empresa estrangeira presta um serviço localizado em Portugal a outra empresa estrangeira. Quem liquida o IVA?",
            "Duas empresas estrangeiras fazem uma venda localizada em Portugal. Há inversão do sujeito passivo?",
            "Prestação de serviços em Portugal por um sujeito passivo não residente a outro sujeito passivo não residente",
            "Operação entre não residentes localizada em Portugal: quem liquida o IVA?",
            "O fornecedor é não residente e o cliente também é não residente. Há autoliquidação?"})
    void bothNonResidentDetected(String question) {
        assertThat(detect(question)).contains(FORNECEDOR_NAO_RESIDENTE, ADQUIRENTE_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Uma empresa portuguesa compra serviços a um fornecedor não residente. Há autoliquidação do IVA?",
            "Um fornecedor não residente presta-me um serviço em Portugal. Tenho de autoliquidar o IVA?",
            "Quando se aplica a inversão do sujeito passivo com fornecedores não residentes?"})
    void onlySupplierNonResident(String question) {
        Set<ApplicabilityMarker> markers = detect(question);
        assertThat(markers).contains(FORNECEDOR_NAO_RESIDENTE).doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @Test
    void buyingFromAForeignCompanyIsNotANonResidentAcquirer() {
        // "a uma empresa estrangeira" é quem vende; quem pergunta (o adquirente) não é não residente
        assertThat(detect("Compro serviços a uma empresa estrangeira. Quem liquida o IVA?"))
                .doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @Test
    void onlyAcquirerNonResident() {
        Set<ApplicabilityMarker> markers = detect("Vendo serviços a um cliente estrangeiro. Liquido IVA?");
        assertThat(markers).contains(ADQUIRENTE_NAO_RESIDENTE).doesNotContain(FORNECEDOR_NAO_RESIDENTE);
    }

    @Test
    void genericReverseChargeHasNoResidenceContext() {
        assertThat(detect("Quando se aplica a autoliquidação do IVA?"))
                .doesNotContain(FORNECEDOR_NAO_RESIDENTE, ADQUIRENTE_NAO_RESIDENTE);
        assertThat(detect("O cliente não é estrangeiro; há inversão?")).doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Sou subarrendatário. Que despesas posso deduzir às rendas do subarrendamento?",
            "Na sublocação de um apartamento, que despesas posso deduzir?",
            "Subarrendei o imóvel que arrendo. Posso abater as obras?"})
    void sublocacaoDetected(String question) {
        assertThat(detect(question)).contains(SUBLOCACAO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?",
            "Sou senhorio. Que despesas posso deduzir às rendas?",
            "Não há subarrendamento: arrendo directamente. Que despesas deduzo?"})
    void sublocacaoNotDetected(String question) {
        assertThat(detect(question)).doesNotContain(SUBLOCACAO);
    }

    // ---- revisão independente (2026-10-14): papéis invertidos, negações, entradas longas -------

    @ParameterizedTest
    @ValueSource(strings = {
            "Uma empresa estrangeira a quem prestamos serviços em Portugal: liquidamos IVA?",
            "Vendemos a uma empresa estrangeira a quem fornecemos bens. Há inversão?",
            "Uma empresa estrangeira efectua aquisições em Portugal. Quem liquida?"})
    void foreignAcquirerIsNotASupplier(String question) {
        assertThat(detect(question)).doesNotContain(FORNECEDOR_NAO_RESIDENTE);
    }

    @Test
    void buyingFromAnotherForeignCompanyIsNotANonResidentAcquirer() {
        assertThat(detect("Compro mercadorias a outra empresa estrangeira. Quem liquida o IVA?"))
                .doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Uma empresa estrangeira que presta serviços em Portugal a outra empresa estrangeira: há inversão?",
            "Os transmitentes não residentes vendem a outras empresas estrangeiras em Portugal."})
    void supplierVerbsAndPlurals(String question) {
        assertThat(detect(question)).contains(FORNECEDOR_NAO_RESIDENTE, ADQUIRENTE_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Não se trata de acto isolado; que IVA posso deduzir?",
            "Não são actos isolados, é a minha actividade. Que IVA deduzo?",
            "Não é considerado um acto isolado. Posso deduzir o IVA?",
            "Sem acto isolado nenhum, posso deduzir o IVA?",
            "Não é propriamente um acto isolado. Que IVA deduzo?",
            "Não se trata, porém, de um acto isolado.",
            "Nunca fiz um acto isolado; que IVA deduzo?",
            "Deixou de ser acto isolado, agora tenho actividade."})
    void negatedIsolatedActIsNotDetected(String question) {
        assertThat(detect(question)).doesNotContain(ACTO_ISOLADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Não é uma sublocação: arrendo directamente. Que despesas deduzo?",
            "O contrato proíbe a sublocação. Que despesas posso deduzir?",
            "Não tenho subarrendamento. Que despesas deduzo às rendas?",
            "Não há lugar a sublocação. Que despesas deduzo?",
            "O senhorio não autoriza a sublocação.",
            "O contrato não permite subarrendar.",
            "Não pretendo subarrendar. Que despesas deduzo?",
            "Sem autorização para sublocar, que despesas deduzo?"})
    void negatedSublocationIsNotDetected(String question) {
        assertThat(detect(question)).doesNotContain(SUBLOCACAO);
    }

    @Test
    void longQuestionsDoNotOverflowTheStack() {
        String repeated = "cliente" + " e".repeat(20_000) + " estrangeiro";
        String words = "ambos " + "tambem ".repeat(5_000) + "nao residentes. " + "acto isolado ".repeat(2_000);
        assertThat(detect(repeated)).doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
        assertThat(detect(words)).contains(ACTO_ISOLADO);
    }

    // ---- re-revisão: substantivos não são verbos, negação adjacente, formas na 1.ª pessoa ------

    @ParameterizedTest
    @ValueSource(strings = {
            "Comprei uma prestação de serviços a outra empresa estrangeira. Quem liquida?",
            "O meu fornecedor português compra a outra empresa estrangeira. Há inversão?",
            "Adquiri por transmissão bens a outra empresa estrangeira."})
    void nounsAreNotSaleVerbs(String question) {
        assertThat(detect(question)).doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Não estou colectado; posso passar acto isolado?",
            "Não tenho actividade aberta; fiz um acto isolado. Posso deduzir o IVA?"})
    void anyNegationMakesTheContextUnaffirmed_safeRejection(String question) {
        // Custo de recall aceite (ADR-009): na dúvida não é afirmado → resposta-limite, nunca o caso oposto.
        assertThat(detect(question)).doesNotContain(ACTO_ISOLADO);
        assertThat(present(question)).contains(ACTO_ISOLADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "A sublocação não é permitida pelo contrato.",
            "A sublocação não está autorizada.",
            "O senhorio recusa a sublocação.",
            "Subarrendar está fora de questão."})
    void negationAfterTheOccurrenceIsNotAffirmed_sublocation(String question) {
        assertThat(detect(question)).doesNotContain(SUBLOCACAO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Um acto isolado não se aplica ao meu caso.",
            "Acto isolado? Não, tenho actividade habitual."})
    void negationAfterTheOccurrenceIsNotAffirmed_isolatedAct(String question) {
        assertThat(detect(question)).doesNotContain(ACTO_ISOLADO);
    }

    @Test
    void firstPersonSaleAndSupplierServingUs() {
        assertThat(detect("Presto serviços a outra empresa estrangeira.")).contains(ADQUIRENTE_NAO_RESIDENTE);
        assertThat(detect("Uma empresa estrangeira que nos presta serviços em Portugal: autoliquidamos?"))
                .contains(FORNECEDOR_NAO_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "O fornecedor presta-me serviços e eu compro a outra empresa estrangeira.",
            "O fornecedor vendeu-me bens; também compro a outra empresa estrangeira."})
    void saleWindowDoesNotCrossIntoAnotherClause(String question) {
        assertThat(detect(question)).doesNotContain(ADQUIRENTE_NAO_RESIDENTE);
    }

    @Test
    void exclusionsSeeNegatedMentions_requiredContextDoesNot() {
        // Exclusão: na dúvida exclui (falha segura = resposta-limite). Contexto obrigatório: só afirmado.
        String question = "O senhorio não autoriza a sublocação. Que despesas posso deduzir?";
        assertThat(present(question)).contains(SUBLOCACAO);
        assertThat(detect(question)).doesNotContain(SUBLOCACAO);
    }

    @Test
    void nonResidentIsAQualityNotANegation() {
        assertThat(detect("Um sujeito passivo não residente fez um acto isolado em Portugal. Pode deduzir o IVA?"))
                .contains(ACTO_ISOLADO);
    }

    // ---- defesa híbrida (só exclusão): ACTIVIDADE_REGULAR e ADQUIRENTE_RESIDENTE --------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Tenho actividade aberta. Posso deduzir o IVA?",
            "Tenho atividade regular e fiz uma venda.",
            "Exerço uma actividade habitual de consultoria.",
            "Estou colectado como trabalhador independente.",
            "Não, tenho actividade habitual."})
    void regularActivityDetected(String question) {
        assertThat(present(question)).contains(ACTIVIDADE_REGULAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Não tenho actividade aberta; fiz um acto isolado.",
            "Sem actividade aberta, posso passar um acto isolado?",
            "Não estou colectado. Posso passar acto isolado?",
            "Deixei de ter actividade aberta.",
            "Que IVA posso deduzir na minha actividade?",
            "O imposto colectado foi superior ao devido."})
    void regularActivityNotDetected(String question) {
        assertThat(present(question)).doesNotContain(ACTIVIDADE_REGULAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ambos não residentes, mas o cliente é uma empresa portuguesa.",
            "O adquirente tem estabelecimento estável em Portugal.",
            "O cliente é residente em Portugal.",
            "Vendo a um cliente português.",
            "Uma empresa portuguesa compra serviços a um fornecedor não residente.",
            "O adquirente tem sede em Portugal."})
    void residentAcquirerDetected(String question) {
        assertThat(present(question)).contains(ADQUIRENTE_RESIDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "A operação localiza-se em Portugal.",
            "O fornecedor é português e vende a uma empresa estrangeira.",
            "O cliente não é português.",
            "O cliente é não residente.",
            "O adquirente não tem estabelecimento estável em Portugal.",
            "Duas empresas estrangeiras fazem uma venda localizada em Portugal.",
            "Uma empresa portuguesa vende a um cliente estrangeiro."})
    void residentAcquirerNotDetected(String question) {
        assertThat(present(question)).doesNotContain(ADQUIRENTE_RESIDENTE);
    }
}
