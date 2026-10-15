package com.knowledgeflow.ai.grounding.scope;

import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.AIMI;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ALTERACAO_ESTADO_CIVIL;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.CALCULO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.CESSACAO_OPCAO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.HERANCA_INDIVISA;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.PAGAMENTO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.RECLAMACAO;
import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AT-FAQ-2721_v2 (opção pela tributação conjunta em AIMI), configuração aprovada em 2026-10-15:
 * REQUIRES {@code AIMI}; EXCLUDES {@code HERANCA_INDIVISA}, {@code RECLAMACAO}, {@code PAGAMENTO},
 * {@code CALCULO}, {@code CESSACAO_OPCAO} e {@code ALTERACAO_ESTADO_CIVIL}. Decisão completa do gate
 * (contradições v1, exclusões, contexto obrigatório), sem LLM.
 */
class Aimi2721V2ApplicabilityTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();
    private final ApplicabilityMarkerDetector detector = new ApplicabilityMarkerDetector();
    private final ScopeCompatibilityGate gate = new ScopeCompatibilityGate();

    private final CandidateScope qa2721v2 = new CandidateScope(
            classifier.classifyCandidate(KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO, "AIMI — tributação conjunta",
                    "Qual é o prazo e o meio para os sujeitos passivos casados ou unidos de facto optarem pela "
                            + "tributação conjunta em AIMI?"),
            Set.of(HERANCA_INDIVISA, RECLAMACAO, PAGAMENTO, CALCULO, CESSACAO_OPCAO, ALTERACAO_ESTADO_CIVIL),
            Set.of(AIMI));

    private String decide(String question) {
        FiscalScope scope = classifier.classify(question);
        ScopeDecision decision = gate.decide(scope, detector.detect(question, scope),
                detector.detectAffirmed(question, scope), qa2721v2);
        return decision.allowed() ? "ALLOW" : decision.reason().name();
    }

    private Set<ApplicabilityMarker> markers(String question) {
        return detector.detect(question, classifier.classify(question));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            A  | Qual é o prazo e o meio para optar pela tributação conjunta em AIMI?                    | ALLOW
            B  | Até quando posso optar pela tributação conjunta do adicional ao IMI?                    | ALLOW
            C  | Tributação conjunta: prazo?                                                             | APPLICABILITY_CONTEXT_MISSING
            E  | Divorciei-me. Como desfazer a opção pela tributação conjunta em AIMI?                   | APPLICABILITY_EXCLUDED
            F  | Quero renunciar à opção pela tributação conjunta em AIMI.                               | APPLICABILITY_EXCLUDED
            G  | Separei-me. A opção conjunta em AIMI mantém-se?                                         | APPLICABILITY_EXCLUDED
            H  | Terminou a união de facto. O que acontece à opção AIMI?                                 | APPLICABILITY_EXCLUDED
            I1 | Os herdeiros de uma herança indivisa podem optar pela tributação conjunta do AIMI?      | APPLICABILITY_EXCLUDED
            I2 | Até quando posso reclamar da liquidação do AIMI?                                        | APPLICABILITY_EXCLUDED
            I3 | Posso fazer o pagamento do AIMI em prestações?                                          | APPLICABILITY_EXCLUDED
            I4 | Como se calcula o AIMI de um casal com tributação conjunta?                             | APPLICABILITY_EXCLUDED
            J  | Qual é o prazo e o meio para os sujeitos passivos casados ou unidos de facto optarem pela tributação conjunta em AIMI? | ALLOW
            K1 | QUAL O PRAZO PARA A OPÇÃO CONJUNTA DO AIMI?                                             | ALLOW
            K2 | Como optamos pela tributação conjunta do adicional ao imposto municipal sobre imóveis?  | ALLOW
            K3 | Somos casados com separação de bens. Podemos optar pela tributação conjunta do AIMI?    | ALLOW
            K4 | Vivemos em união de facto. Até quando entregamos a declaração de opção do AIMI?         | ALLOW
            L1 | Divorciados podem manter a opção conjunta do AIMI?                                      | APPLICABILITY_EXCLUDED
            L2 | Como cancelo a opção pela tributação conjunta do AIMI?                                  | APPLICABILITY_EXCLUDED
            L3 | Deixámos de viver em união de facto; a opção AIMI cessa?                                | APPLICABILITY_EXCLUDED
            L4 | Quero deixar de optar pela tributação conjunta do AIMI.                                 | APPLICABILITY_EXCLUDED
            """)
    void heldOut(String id, String question, String expected) {
        assertThat(decide(question)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Qual é o prazo da tributação conjunta no IMI?",
            "Tributação conjunta dos imóveis: até quando?",
            "Qual é o prazo para pagar o IMI?",
            "Tenho vários imóveis. Como funciona a tributação conjunta?",
            "Prazo da declaração de rendimentos conjunta?"})
    void noAimiContextWithoutTheTaxName(String question) {
        // D e afins: tributação conjunta, IMI isolado, imóveis ou prazo não provam AIMI
        assertThat(markers(question)).doesNotContain(AIMI);
        assertThat(decide(question)).isNotEqualTo("ALLOW");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AIMI", "aimi", "Adicional ao IMI", "adicional do IMI",
            "adicional ao imposto municipal sobre imóveis", "ADICIONAL AO IMPOSTO MUNICIPAL SOBRE OS IMÓVEIS"})
    void aimiByName(String text) {
        assertThat(markers("Prazo da opção conjunta: " + text + "?")).contains(AIMI);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Somos casados com separação de bens.",
            "Declaramos separadamente o IRS.",
            "Os prédios estão separados por freguesia.",
            "Somos casados e queremos optar pela tributação conjunta.",
            "Vivemos em união de facto há dois anos.",
            "Os imóveis do casal estão separados em duas matrizes."})
    void ordinaryCoupleQuestionsAreNotCivilStatusChanges(String question) {
        assertThat(markers(question)).doesNotContain(ALTERACAO_ESTADO_CIVIL, CESSACAO_OPCAO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Divorciei-me no ano passado.", "Estamos separados de facto.", "Fim da união de facto em março.",
            "Acabou a nossa união de facto.", "A renúncia à opção faz-se como?", "Podemos desistir da opção?",
            "Revogar a opção conjunta é possível?"})
    void exclusionSignals(String question) {
        Set<ApplicabilityMarker> found = markers(question);
        assertThat(found.contains(ALTERACAO_ESTADO_CIVIL) || found.contains(CESSACAO_OPCAO)).as(question).isTrue();
    }
}
