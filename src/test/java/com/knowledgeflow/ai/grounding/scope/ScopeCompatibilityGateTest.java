package com.knowledgeflow.ai.grounding.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.IncomeCategory;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.TaxDomain;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regras de contradição (M4-SCOPE) e critério de aceitação sobre o corpus publicado do piloto
 * (4 Q&amp;A) com as perguntas da bateria M4-RECAL. Os limites conhecidos ficam explícitos como
 * ALLOW: o gate não os resolve e não recebe termos à medida para os resolver. Os casos R01–R05 vêm
 * da revisão independente (falsos positivos do dicionário, corrigidos nas versões 2026-10-01.2 e .3).
 */
class ScopeCompatibilityGateTest {

    private final ScopeCompatibilityGate gate = new ScopeCompatibilityGate();
    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();

    /** Âmbitos derivados exactamente como em produção (tema, subtema, pergunta) das 4 Q&amp;A publicadas. */
    private final Map<String, FiscalScope> corpus = Map.of(
            "2721", classifier.classifyCandidate(KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO, "AIMI — tributação conjunta",
                    "Qual é o prazo e o meio para os sujeitos passivos casados ou unidos de facto optarem pela "
                            + "tributação conjunta em AIMI?"),
            "5795", classifier.classifyCandidate(KnowledgeTopic.IRS, "Retenção na fonte — categoria H",
                    "Um pensionista pode pedir retenção mensal quando cada pensão, isoladamente, corresponde a "
                            + "taxa de 0%?"),
            "5930", classifier.classifyCandidate(KnowledgeTopic.IRS, "Categoria F — gastos dedutíveis",
                    "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?"),
            "CIVA", classifier.classifyCandidate(KnowledgeTopic.IVA, null,
                    "Durante quanto tempo deve um sujeito passivo de IVA conservar os registos e respectivos "
                            + "documentos de suporte?"));

    // ---- regras -------------------------------------------------------------------------------

    @Test
    void silenceOnEitherSide_isNeverAContradiction() {
        FiscalScope empty = scope(Set.of(), Set.of(), Set.of());
        FiscalScope full = scope(Set.of(TaxDomain.IVA), Set.of(), Set.of(FiscalOperation.CONSERVACAO));
        assertThat(gate.decide(empty, full)).isEqualTo(ScopeDecision.allow(ScopeReason.NO_CONTRADICTION));
        assertThat(gate.decide(full, empty)).isEqualTo(ScopeDecision.allow(ScopeReason.NO_CONTRADICTION));
    }

    @Test
    void disjointDomains_areRejected_butAnyCommonDomainIsEnough() {
        FiscalScope vat = scope(Set.of(TaxDomain.IVA), Set.of(), Set.of());
        assertThat(gate.decide(scope(Set.of(TaxDomain.IRS), Set.of(), Set.of()), vat).reason())
                .isEqualTo(ScopeReason.DOMAIN_MISMATCH);
        assertThat(gate.decide(scope(Set.of(TaxDomain.IRS, TaxDomain.IVA), Set.of(), Set.of()), vat).allowed())
                .isTrue();
    }

    @Test
    void everyQueryCategory_mustBeCoveredByTheCandidate() {
        FiscalScope pensions = scope(Set.of(TaxDomain.IRS), Set.of(IncomeCategory.H), Set.of());
        assertThat(gate.decide(scope(Set.of(TaxDomain.IRS), Set.of(IncomeCategory.H), Set.of()), pensions).allowed())
                .isTrue();
        assertThat(gate.decide(scope(Set.of(TaxDomain.IRS), Set.of(IncomeCategory.A, IncomeCategory.H), Set.of()),
                pensions).reason()).isEqualTo(ScopeReason.CATEGORY_NOT_COVERED);
    }

    @Test
    void disjointOperations_areRejected_butAnyCommonOperationIsEnough() {
        FiscalScope deduction = scope(Set.of(), Set.of(), Set.of(FiscalOperation.DEDUCAO));
        assertThat(gate.decide(scope(Set.of(), Set.of(), Set.of(FiscalOperation.RETENCAO)), deduction).reason())
                .isEqualTo(ScopeReason.OPERATION_MISMATCH);
        assertThat(gate.decide(scope(Set.of(), Set.of(),
                Set.of(FiscalOperation.RETENCAO, FiscalOperation.DEDUCAO)), deduction).allowed()).isTrue();
    }

    // ---- aceitação sobre o corpus ------------------------------------------------------------

    @ParameterizedTest(name = "{0} × {2} → {3}")
    @CsvSource(delimiter = '|', textBlock = """
            Q01 | Qual é o prazo e o meio para os sujeitos passivos casados ou unidos de facto optarem pela tributação conjunta em AIMI? | 2721 | NO_CONTRADICTION
            Q02 | Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?                                   | 5930 | NO_CONTRADICTION
            Q03 | Durante quanto tempo deve um sujeito passivo de IVA conservar os registos e respectivos documentos de suporte?          | CIVA | NO_CONTRADICTION
            Q04 | Quais os gastos dedutíveis no arrendamento para efeitos de IRS?                                                         | 5930 | NO_CONTRADICTION
            Q05 | Posso abater as obras de conservação às rendas que recebo?                                                              | 5930 | NO_CONTRADICTION
            Q06 | Os juros do crédito de um imóvel arrendado reduzem o rendimento da categoria F?                                         | 5930 | NO_CONTRADICTION
            Q07 | Como é que um casal opta pela tributação conjunta no adicional ao IMI?                                                  | 2721 | NO_CONTRADICTION
            Q09 | Durante quantos anos tenho de guardar as facturas para efeitos de IVA?                                                  | CIVA | NO_CONTRADICTION
            Q10 | Qual é o prazo de conservação dos documentos para IVA?                                                                  | CIVA | NO_CONTRADICTION
            Q28 | Um pensionista pode pedir retenção mensal quando cada pensão, isoladamente, corresponde a taxa de 0%?                   | 5795 | NO_CONTRADICTION
            Q29 | Tenho duas pensões e nenhuma tem retenção de IRS; posso pedir para me reterem imposto?                                  | 5795 | NO_CONTRADICTION
            Q30 | Recebo duas pensões com retenção a 0%. Posso pedir retenção mensal?                                                     | 5795 | NO_CONTRADICTION
            Q31 | Tenho pensões pagas por entidades diferentes. Posso pedir que uma delas faça retenção?                                  | 5795 | NO_CONTRADICTION
            Q32 | Se cada pensão isoladamente não tem retenção, posso pedir que considerem o total das duas?                              | 5795 | NO_CONTRADICTION
            Q33 | A entidade que paga a pensão maior pode reter IRS tendo em conta a outra pensão?                                        | 5795 | NO_CONTRADICTION
            Q34 | Posso escolher uma taxa superior de retenção na minha pensão?                                                           | 5795 | NO_CONTRADICTION
            Q45 | Durante quanto tempo tenho de conservar os documentos de IRS?                                                           | CIVA | DOMAIN_MISMATCH
            Q45 | Durante quanto tempo tenho de conservar os documentos de IRS?                                                           | 5795 | OPERATION_MISMATCH
            Q11 | As mais-valias da venda de um imóvel arrendado são tributadas em IRS?                                                   | 5930 | CATEGORY_NOT_COVERED
            Q12 | Como se calcula o IMI de um prédio urbano?                                                                              | 2721 | DOMAIN_MISMATCH
            Q13 | Qual o prazo para emitir uma factura em IVA?                                                                            | CIVA | OPERATION_MISMATCH
            Q15 | Qual a taxa de IRC para pequenas e médias empresas?                                                                     | 5930 | DOMAIN_MISMATCH
            Q17 | Qual o prazo de entrega da declaração periódica de IVA?                                                                 | CIVA | OPERATION_MISMATCH
            Q19 | Posso deduzir o IVA das despesas de um acto isolado?                                                                    | 5930 | DOMAIN_MISMATCH
            Q35 | Tenho dois salários e nenhum faz retenção. Posso pedir a uma entidade que retenha pelo total?                           | 5795 | CATEGORY_NOT_COVERED
            Q36 | Tenho rendimentos da categoria B sem retenção. Posso pedir retenção voluntária?                                         | 5795 | CATEGORY_NOT_COVERED
            Q37 | Recebo pensão e salário. A entidade da pensão pode considerar o salário para retenção?                                  | 5795 | CATEGORY_NOT_COVERED
            Q41 | Como funciona a retenção na fonte da categoria A?                                                                       | 5795 | CATEGORY_NOT_COVERED
            Q47 | Que despesas são dedutíveis no AIMI?                                                                                    | 5930 | DOMAIN_MISMATCH
            Q48 | Há retenção na fonte sobre as rendas que recebo?                                                                        | 5795 | CATEGORY_NOT_COVERED
            Q48 | Há retenção na fonte sobre as rendas que recebo?                                                                        | 5930 | OPERATION_MISMATCH
            Q49 | Os pensionistas casados podem optar pela tributação conjunta em IRS?                                                    | 2721 | DOMAIN_MISMATCH
            Q50 | Que documentos de suporte devo guardar dos rendimentos prediais?                                                        | 5930 | OPERATION_MISMATCH
            Q50 | Que documentos de suporte devo guardar dos rendimentos prediais?                                                        | CIVA | DOMAIN_MISMATCH
            Q14 | As despesas de condomínio de uma casa própria permanente são dedutíveis?                                                | 5930 | NO_CONTRADICTION
            Q39 | Uma pensão é portuguesa e outra estrangeira. Aplica-se a mesma regra?                                                   | 5795 | NO_CONTRADICTION
            R01 | Obras de conservação no imóvel arrendado reduzem o IRS das rendas?                                                    | 5930 | NO_CONTRADICTION
            R02 | Como optar pela tributação conjunta no adicional do IMI?                                                                | 2721 | NO_CONTRADICTION
            R03 | As rendas recebidas por uma empresa são gasto dedutível em IRC?                                                         | 5930 | DOMAIN_MISMATCH
            R04 | Posso deduzir o IVA das rendas que pago?                                                                                | 5930 | DOMAIN_MISMATCH
            R05 | Sou reformado e recebo rendas; que despesas posso deduzir?                                                              | 5930 | NO_CONTRADICTION
            Q24 | E os prazos?                                                                                                            | 2721 | NO_CONTRADICTION
            """)
    void corpusAcceptance(String id, String question, String candidate, ScopeReason expected) {
        ScopeDecision decision = gate.decide(classifier.classify(question), corpus.get(candidate));

        assertThat(decision.reason()).as("%s × %s", id, candidate).isEqualTo(expected);
        assertThat(decision.allowed()).isEqualTo(expected == ScopeReason.NO_CONTRADICTION);
    }

    private static FiscalScope scope(Set<TaxDomain> d, Set<IncomeCategory> c, Set<FiscalOperation> o) {
        return new FiscalScope(d, c, o);
    }
}
