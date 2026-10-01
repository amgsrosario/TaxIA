package com.knowledgeflow.ai.grounding.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.IncomeCategory;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.TaxDomain;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import org.junit.jupiter.api.Test;

/** Classificador determinístico de âmbito (M4-SCOPE): normalização, dimensões e silêncio. */
class FiscalScopeClassifierTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();

    @Test
    void normalize_removesAccentsCaseHyphensAndPunctuation() {
        assertThat(FiscalScopeClassifier.normalize("  Mais-Valias, RETENÇÃO   na Fonte?! "))
                .isEqualTo("mais valias retencao na fonte");
        assertThat(FiscalScopeClassifier.normalize(null)).isEmpty();
    }

    @Test
    void detectsTaxDomainsByWholeWord() {
        assertThat(classifier.classify("Qual a taxa de IRC?").domains()).containsExactly(TaxDomain.IRC);
        assertThat(classifier.classify("Prazo do IVA").domains()).containsExactly(TaxDomain.IVA);
        assertThat(classifier.classify("Como se calcula o IMI?").domains()).containsExactly(TaxDomain.IMI);
        assertThat(classifier.classify("IMT na compra de casa").domains()).containsExactly(TaxDomain.IMT);
        // "iva" dentro de outra palavra não conta
        assertThat(classifier.classify("Atividade privada e diversa").domains()).isEmpty();
    }

    @Test
    void aimi_isNotAlsoImi() {
        assertThat(classifier.classify("Tributação conjunta em AIMI").domains()).containsExactly(TaxDomain.AIMI);
        assertThat(classifier.classify("Como opto no adicional ao IMI?").domains()).containsExactly(TaxDomain.AIMI);
        assertThat(classifier.classify("Tributação conjunta no adicional do IMI").domains())
                .containsExactly(TaxDomain.AIMI);
        assertThat(classifier.classify("Adicional de IMI de um casal").domains()).containsExactly(TaxDomain.AIMI);
        assertThat(classifier.classify("AIMI e IMI do mesmo prédio").domains())
                .containsExactlyInAnyOrder(TaxDomain.AIMI, TaxDomain.IMI);
    }

    @Test
    void incomeCategories_implyIrs() {
        assertThat(classifier.classify("Tenho dois salários").incomeCategories()).containsExactly(IncomeCategory.A);
        assertThat(classifier.classify("Passo recibos verdes").incomeCategories()).containsExactly(IncomeCategory.B);
        assertThat(classifier.classify("As rendas que recebo do inquilino").incomeCategories())
                .containsExactly(IncomeCategory.F);
        assertThat(classifier.classify("Mais-valias de acções").incomeCategories()).containsExactly(IncomeCategory.G);
        assertThat(classifier.classify("Tenho duas pensões").incomeCategories()).containsExactly(IncomeCategory.H);
        assertThat(classifier.classify("Recebo pensão e salário").incomeCategories())
                .containsExactlyInAnyOrder(IncomeCategory.A, IncomeCategory.H);
        assertThat(classifier.classify("Tenho duas pensões").domains()).containsExactly(TaxDomain.IRS);
    }

    @Test
    void categoryNextToAPropertyTax_stillImpliesIrs() {
        // o IMI de um prédio arrendado é gasto da categoria F
        assertThat(classifier.classify("Posso descontar o IMI da casa que arrendo aos rendimentos prediais?")
                .domains()).containsExactlyInAnyOrder(TaxDomain.IMI, TaxDomain.IRS);
    }

    @Test
    void ivaMentionedBySelfEmployed_keepsIrsForOtherCategories() {
        assertThat(classifier.classify("Passo recibos verdes com IVA; tenho retenção na fonte?").domains())
                .containsExactlyInAnyOrder(TaxDomain.IVA, TaxDomain.IRS);
        assertThat(classifier.classify("Trabalhador independente isento de IVA: há retenção na fonte?")
                .incomeCategories()).containsExactly(IncomeCategory.B);
    }

    @Test
    void categoryWithIrcOrIva_doesNotAddIrs() {
        assertThat(classifier.classify("As rendas recebidas por uma empresa são gasto dedutível em IRC?").domains())
                .containsExactly(TaxDomain.IRC);
        assertThat(classifier.classify("Posso deduzir o IVA das rendas que pago?").domains())
                .containsExactly(TaxDomain.IVA);
    }

    @Test
    void negativeDictionary_commonWordsWithOtherMeanings() {
        // conservação do imóvel e guarda de menores não são conservação documental
        assertThat(classifier.classify("Obras de conservação no imóvel arrendado reduzem o IRS das rendas?")
                .operations()).isEmpty();
        assertThat(classifier.classify("Com guarda partilhada, quem declara os filhos?").operations()).isEmpty();
        // conservação documental com o objecto antes ou depois do verbo
        assertThat(classifier.classify("Que documentos de suporte devo guardar?").operations())
                .containsExactly(FiscalOperation.CONSERVACAO);
        assertThat(classifier.classify("Prazo de conservação dos documentos").operations())
                .containsExactly(FiscalOperation.CONSERVACAO);
        // depois do objecto só conta o verbo: "faturas das obras de conservação" é a conservação do bem
        assertThat(classifier.classify("As faturas das obras de conservação contam para o IRS das rendas?")
                .operations()).isEmpty();
        assertThat(classifier.classify("Os recibos das obras de conservação do prédio arrendado entram no IRS?")
                .operations()).isEmpty();
        assertThat(classifier.classify("As faturas devem ser guardadas quanto tempo?").operations())
                .containsExactly(FiscalOperation.CONSERVACAO);
        // conservatória do registo e recibos verdes não são conservação documental
        assertThat(classifier.classify("Preciso de certidão da conservatória do registo predial para o IMT?")
                .operations()).isEmpty();
        assertThat(classifier.classify("Passo recibos verdes e guardo dinheiro numa conta; pago IRS?")
                .operations()).isEmpty();
        // data de vencimento não é salário
        assertThat(classifier.classify("A renda com vencimento em dezembro conta para que ano?").incomeCategories())
                .containsExactly(IncomeCategory.F);
        // "a categoria a que pertencem" não é a categoria A
        assertThat(classifier.classify("Qual a categoria a que pertencem as rendas?").incomeCategories())
                .containsExactly(IncomeCategory.F);
        // renda fixa / vitalícia não são rendimentos prediais
        assertThat(classifier.classify("Fundos de renda fixa pagam IRS?").incomeCategories()).isEmpty();
        assertThat(classifier.classify("Uma renda vitalícia é tributada?").incomeCategories()).isEmpty();
        // a pessoa (reformado, pensionista) não é o rendimento
        assertThat(classifier.classify("Sou reformado e recebo rendas; que despesas posso deduzir?")
                .incomeCategories()).containsExactly(IncomeCategory.F);
    }

    @Test
    void longTaxNames_includingPreReformSpelling() {
        assertThat(classifier.classify("Imposto sobre o rendimento das pessoas colectivas").domains())
                .containsExactly(TaxDomain.IRC);
        assertThat(classifier.classify("Imposto sobre o rendimento das pessoas coletivas").domains())
                .containsExactly(TaxDomain.IRC);
    }

    @Test
    void ambiguousGeneralWords_areNotCategories() {
        // "rendimento" não é "renda"; "reforma" isolada (obras, reforma legislativa) não é pensão.
        assertThat(classifier.classify("O rendimento global").incomeCategories()).isEmpty();
        assertThat(classifier.classify("A reforma da casa").incomeCategories()).isEmpty();
        assertThat(classifier.classify("Sou pensionista").incomeCategories()).isEmpty();
    }

    @Test
    void detectsOperations() {
        assertThat(classifier.classify("Posso pedir retenção mensal?").operations())
                .containsExactly(FiscalOperation.RETENCAO);
        assertThat(classifier.classify("Que despesas são dedutíveis?").operations())
                .containsExactly(FiscalOperation.DEDUCAO);
        assertThat(classifier.classify("Quanto tempo devo guardar as facturas?").operations())
                .containsExactly(FiscalOperation.CONSERVACAO);
        assertThat(classifier.classify("Casados tributados conjuntamente").operations())
                .containsExactly(FiscalOperation.TRIBUTACAO_CONJUNTA);
        assertThat(classifier.classify("Como se calcula?").operations()).containsExactly(FiscalOperation.CALCULO);
        assertThat(classifier.classify("Entrega da declaração periódica").operations())
                .containsExactly(FiscalOperation.DECLARACAO);
        assertThat(classifier.classify("Prazo para emitir factura").operations())
                .containsExactly(FiscalOperation.EMISSAO);
    }

    @Test
    void silence_isEmptyInEveryDimension() {
        FiscalScope scope = classifier.classify("E os prazos?");
        assertThat(scope.domains()).isEmpty();
        assertThat(scope.incomeCategories()).isEmpty();
        assertThat(scope.operations()).isEmpty();
        assertThat(classifier.classify(null).domains()).isEmpty();
    }

    @Test
    void candidate_usesTopicSubtopicAndQuestion() {
        FiscalScope pension = classifier.classifyCandidate(KnowledgeTopic.IRS, "Retenção na fonte — categoria H",
                "Um pensionista pode pedir retenção mensal quando cada pensão corresponde a taxa de 0%?");
        assertThat(pension.domains()).containsExactly(TaxDomain.IRS);
        assertThat(pension.incomeCategories()).containsExactly(IncomeCategory.H);
        assertThat(pension.operations()).containsExactly(FiscalOperation.RETENCAO);

        FiscalScope vat = classifier.classifyCandidate(KnowledgeTopic.IVA, null,
                "Durante quanto tempo deve um sujeito passivo conservar os registos?");
        assertThat(vat.domains()).containsExactly(TaxDomain.IVA);
        assertThat(vat.operations()).containsExactly(FiscalOperation.CONSERVACAO);
    }

    @Test
    void candidate_proceduralTopic_doesNotDetermineTheTax() {
        FiscalScope aimi = classifier.classifyCandidate(KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO,
                "AIMI — tributação conjunta", "Qual o prazo para optar?");
        assertThat(aimi.domains()).containsExactly(TaxDomain.AIMI);

        FiscalScope none = classifier.classifyCandidate(KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO, null, "Qual o prazo?");
        assertThat(none.domains()).isEmpty();
        assertThat(classifier.classifyCandidate(null, null, null).domains()).isEmpty();
    }
}
