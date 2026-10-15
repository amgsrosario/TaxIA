package com.knowledgeflow.ai.grounding.scope;

import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ACTIVIDADE_REGULAR;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ACTO_ISOLADO;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.ADQUIRENTE_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.FORNECEDOR_NAO_RESIDENTE;
import static com.knowledgeflow.knowledge.enums.ApplicabilityMarker.SUBLOCACAO;
import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Held-out da defesa híbrida (C9-A, ADR-009) com a configuração editorial aprovada em 2026-10-15:
 * <ul>
 *   <li>AT-FAQ-0959: REQUIRES {@code ACTO_ISOLADO} + EXCLUDES {@code ACTIVIDADE_REGULAR};</li>
 *   <li>AT-FAQ-4624: REQUIRES {@code FORNECEDOR_NAO_RESIDENTE} E {@code ADQUIRENTE_NAO_RESIDENTE} +
 *       EXCLUDES {@code ADQUIRENTE_RESIDENTE};</li>
 *   <li>AT-FAQ-5930: EXCLUDES {@code SUBLOCACAO} (as exclusões existentes não interferem nestes casos).</li>
 * </ul>
 * Decisão de gate completa (contradições v1, exclusões, contexto obrigatório), sem LLM, threshold à
 * parte. Falha segura: nas perguntas mistas ou contraditórias o candidato é rejeitado.
 */
class HybridApplicabilityHeldOutTest {

    private final FiscalScopeClassifier classifier = new FiscalScopeClassifier();
    private final ApplicabilityMarkerDetector detector = new ApplicabilityMarkerDetector();
    private final ScopeCompatibilityGate gate = new ScopeCompatibilityGate();

    private final CandidateScope qa0959 = new CandidateScope(
            classifier.classifyCandidate(KnowledgeTopic.IVA, "Direito à dedução",
                    "Quem realiza um acto isolado pode deduzir o IVA suportado nas despesas relacionadas com essa operação?"),
            Set.of(ACTIVIDADE_REGULAR), Set.of(ACTO_ISOLADO));
    private final CandidateScope qa4624 = new CandidateScope(
            classifier.classifyCandidate(KnowledgeTopic.IVA, "Inversão do sujeito passivo",
                    "Aplica-se a inversão do sujeito passivo quando fornecedor e adquirente são ambos não residentes "
                            + "e a operação se localiza em Portugal?"),
            Set.of(ADQUIRENTE_RESIDENTE), Set.of(FORNECEDOR_NAO_RESIDENTE, ADQUIRENTE_NAO_RESIDENTE));
    private final CandidateScope qa5930 = new CandidateScope(
            classifier.classifyCandidate(KnowledgeTopic.IRS, "Categoria F — gastos dedutíveis",
                    "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?"),
            Set.of(SUBLOCACAO), Set.of());

    private ScopeReason decide(String question, CandidateScope candidate) {
        FiscalScope scope = classifier.classify(question);
        ScopeDecision decision = gate.decide(scope, detector.detect(question, scope),
                detector.detectAffirmed(question, scope), candidate);
        return decision.allowed() ? null : decision.reason();
    }

    @ParameterizedTest(name = "0959 {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            A  | Fiz um acto isolado. Posso deduzir o IVA?                                                              | ACCEPT
            B  | Em que condições posso deduzir o IVA suportado?                                                        | APPLICABILITY_CONTEXT_MISSING
            C  | Tenho actividade aberta. Posso deduzir o IVA das despesas da minha actividade?                         | APPLICABILITY_EXCLUDED
            D  | Tenho actividade regular mas fiz também um acto isolado. Posso deduzir o IVA desta operação?           | APPLICABILITY_EXCLUDED
            M1 | Tenho actividade aberta e fiz uma operação isolada. Posso deduzir o IVA?                               | APPLICABILITY_EXCLUDED
            M2 | Estou colectado e pratiquei um acto isolado. Há dedução do IVA?                                        | APPLICABILITY_EXCLUDED
            M3 | Não, tenho actividade habitual; fiz também um acto isolado. Posso deduzir o IVA?                       | APPLICABILITY_EXCLUDED
            N1 | Não é um acto isolado: tenho actividade regular. Posso deduzir o IVA?                                  | APPLICABILITY_EXCLUDED
            N2 | Não tenho actividade aberta; fiz um acto isolado. Posso deduzir o IVA?                                 | APPLICABILITY_CONTEXT_MISSING
            N3 | Nunca fiz um acto isolado. Que IVA posso deduzir?                                                      | APPLICABILITY_CONTEXT_MISSING
            G1 | Que IVA posso deduzir na minha actividade?                                                             | APPLICABILITY_CONTEXT_MISSING
            G2 | Posso deduzir o IVA?                                                                                   | APPLICABILITY_CONTEXT_MISSING
            S1 | Passei um acto isolado nas Finanças. Posso recuperar o IVA das compras para essa venda?                 | ACCEPT
            """)
    void heldOut0959(String id, String question, String expected) {
        assertThat(decide(question, qa0959)).as(id).isEqualTo(expected.equals("ACCEPT") ? null : ScopeReason.valueOf(expected));
    }

    @ParameterizedTest(name = "4624 {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            A  | Fornecedor e adquirente são ambos não residentes e a operação localiza-se em Portugal. Há inversão do sujeito passivo?          | ACCEPT
            B  | Quando se aplica a inversão do sujeito passivo com fornecedores não residentes?                                                 | APPLICABILITY_CONTEXT_MISSING
            C  | Uma empresa portuguesa compra serviços a um fornecedor não residente. Há autoliquidação?                                        | APPLICABILITY_EXCLUDED
            D  | Fornecedor e adquirente são ambos não residentes, mas o adquirente tem estabelecimento estável em Portugal. Há inversão?        | APPLICABILITY_EXCLUDED
            E  | Ambos não residentes, mas o cliente é uma empresa portuguesa. Há inversão do sujeito passivo?                                   | APPLICABILITY_EXCLUDED
            F  | Um fornecedor não residente presta-me um serviço em Portugal. Tenho de autoliquidar o IVA?                                      | APPLICABILITY_CONTEXT_MISSING
            G  | Vendo serviços a um cliente estrangeiro. Há inversão do sujeito passivo?                                                        | APPLICABILITY_CONTEXT_MISSING
            H  | Compro mercadorias a outra empresa estrangeira. Há inversão do sujeito passivo?                                                 | APPLICABILITY_CONTEXT_MISSING
            I  | Uma empresa estrangeira a quem prestamos serviços em Portugal: há inversão do sujeito passivo?                                   | APPLICABILITY_CONTEXT_MISSING
            J  | Há inversão do sujeito passivo?                                                                                                 | APPLICABILITY_CONTEXT_MISSING
            K  | Duas empresas estrangeiras fazem uma venda localizada em Portugal. Há inversão do sujeito passivo?                              | ACCEPT
            L  | Uma empresa estrangeira presta um serviço localizado em Portugal a outra empresa estrangeira. Quem liquida o IVA?               | ACCEPT
            M  | Ambos não residentes; o cliente não é português nem tem estabelecimento estável em Portugal. Há inversão?                       | ACCEPT
            N  | O fornecedor estrangeiro vende a um cliente português. Há inversão do sujeito passivo?                                          | APPLICABILITY_EXCLUDED
            """)
    void heldOut4624(String id, String question, String expected) {
        assertThat(decide(question, qa4624)).as(id).isEqualTo(expected.equals("ACCEPT") ? null : ScopeReason.valueOf(expected));
    }

    @ParameterizedTest(name = "5930 {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            A | Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento? | ACCEPT
            B | Sou subarrendatário. Que despesas posso deduzir às rendas do subarrendamento?         | APPLICABILITY_EXCLUDED
            C | Na sublocação de um apartamento, que despesas posso deduzir?                          | APPLICABILITY_EXCLUDED
            D | Sou senhorio. Que despesas posso deduzir às rendas?                                   | ACCEPT
            """)
    void heldOut5930(String id, String question, String expected) {
        assertThat(decide(question, qa5930)).as(id).isEqualTo(expected.equals("ACCEPT") ? null : ScopeReason.valueOf(expected));
    }
}
