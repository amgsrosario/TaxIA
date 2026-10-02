package com.knowledgeflow.ai.grounding.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Matriz funcional do gate v2 (M4-SCOPE-V2) sobre fixtures descartáveis com o âmbito derivado das 4
 * Q&amp;A do piloto e exclusões de EXEMPLO (as reais são definidas editorialmente, não aqui). Prova a
 * ordem: contradições v1 primeiro, exclusões depois; e que, sem marcador, nada muda.
 */
class ApplicabilityGateMatrixTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();
    private final ApplicabilityMarkerDetector detector = new ApplicabilityMarkerDetector();
    private final ScopeCompatibilityGate gate = new ScopeCompatibilityGate();

    private final Map<String, CandidateScope> corpus = Map.of(
            "2721", new CandidateScope(classifier.classifyCandidate(KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO,
                    "AIMI — tributação conjunta", "Qual é o prazo e o meio para os sujeitos passivos casados ou "
                            + "unidos de facto optarem pela tributação conjunta em AIMI?"),
                    EnumSet.of(ApplicabilityMarker.HERANCA_INDIVISA, ApplicabilityMarker.CALCULO,
                            ApplicabilityMarker.RECLAMACAO, ApplicabilityMarker.PAGAMENTO)),
            "5795", new CandidateScope(classifier.classifyCandidate(KnowledgeTopic.IRS,
                    "Retenção na fonte — categoria H", "Um pensionista pode pedir retenção mensal quando cada "
                            + "pensão, isoladamente, corresponde a taxa de 0%?"),
                    EnumSet.of(ApplicabilityMarker.PENSAO_ESTRANGEIRA, ApplicabilityMarker.RETENCAO_OBRIGATORIA)),
            "5930", new CandidateScope(classifier.classifyCandidate(KnowledgeTopic.IRS,
                    "Categoria F — gastos dedutíveis",
                    "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?"),
                    EnumSet.of(ApplicabilityMarker.INQUILINO, ApplicabilityMarker.HABITACAO_PROPRIA)),
            "CIVA", new CandidateScope(classifier.classifyCandidate(KnowledgeTopic.IVA, null,
                    "Durante quanto tempo deve um sujeito passivo de IVA conservar os registos e respectivos "
                            + "documentos de suporte?"), Set.of()));

    @ParameterizedTest(name = "{1} × {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            Como inquilino, posso abater as obras que fiz na casa?                         | 5930 | APPLICABILITY_EXCLUDED
            Obras de conservação na habitação própria são dedutíveis no IRS?               | 5930 | APPLICABILITY_EXCLUDED
            Sou senhorio; o condomínio do andar arrendado abate às rendas?                 | 5930 | NO_CONTRADICTION
            As mais-valias da venda do apartamento arrendado pagam IRS?                    | 5930 | CATEGORY_NOT_COVERED
            O inquilino tem de reter IRS sobre a renda?                                    | 5930 | OPERATION_MISMATCH
            Tenho uma pensão estrangeira e outra nacional; posso pedir retenção mensal?    | 5795 | APPLICABILITY_EXCLUDED
            A seguradora é obrigada a fazer retenção sobre o complemento de pensão?        | 5795 | APPLICABILITY_EXCLUDED
            Tenho três pensões a 0%; posso pedir retenção mensal pelo total?               | 5795 | NO_CONTRADICTION
            Tenho dois salários sem retenção; posso pedir retenção?                        | 5795 | CATEGORY_NOT_COVERED
            Passo recibos verdes; posso pedir retenção?                                    | 5795 | CATEGORY_NOT_COVERED
            Os herdeiros de uma herança indivisa podem optar pelo AIMI conjunto?           | 2721 | APPLICABILITY_EXCLUDED
            Até quando podemos reclamar da liquidação do AIMI conjunto?                    | 2721 | APPLICABILITY_EXCLUDED
            Podemos fazer o pagamento do AIMI conjunto em prestações?                      | 2721 | APPLICABILITY_EXCLUDED
            Como se calcula o AIMI de um casal em tributação conjunta?                     | 2721 | APPLICABILITY_EXCLUDED
            Queremos que o adicional do IMI seja calculado em conjunto; até quando optamos? | 2721 | NO_CONTRADICTION
            Até quando um casal pode optar pela tributação conjunta no AIMI?               | 2721 | NO_CONTRADICTION
            O AIMI é calculado com base no VPT de todos os imóveis do casal?               | 2721 | OPERATION_MISMATCH
            O inquilino paga-me a renda; que gastos meus posso deduzir às rendas?           | 5930 | NO_CONTRADICTION
            Quanto tempo devo conservar as facturas para efeitos de IVA?                   | CIVA | NO_CONTRADICTION
            Quanto tempo devo conservar os documentos de IRS?                              | CIVA | DOMAIN_MISMATCH
            Sou inquilino; quanto tempo guardo as facturas de IVA?                         | CIVA | NO_CONTRADICTION
            """)
    void matrix(String question, String candidate, ScopeReason expected) {
        FiscalScope query = classifier.classify(question);
        ScopeDecision decision = gate.decide(query, detector.detect(question, query), corpus.get(candidate));

        assertThat(decision.reason()).isEqualTo(expected);
        assertThat(decision.allowed()).isEqualTo(expected == ScopeReason.NO_CONTRADICTION);
    }
}
