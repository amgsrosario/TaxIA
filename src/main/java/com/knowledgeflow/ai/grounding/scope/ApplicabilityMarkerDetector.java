package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detecção determinística, na pergunta, dos marcadores de aplicabilidade (M4-SCOPE-V2, ADR-004).
 * Mesma normalização do {@link FiscalScopeClassifier}; sem modelos. Um marcador só tem efeito
 * quando a Q&amp;A candidata o declara como exclusão — detectá-lo sozinho não rejeita nada.
 *
 * <p>Os padrões cobrem a situação ou o papel de quem pergunta, não perguntas concretas;
 * expressões ambíguas ficam de fora (p. ex. "casa onde moro", "herdeiro"/"herança" sem
 * indivisão, "pagar" isolado, a mera menção do inquilino pelo senhorio). Na dúvida o marcador
 * não dispara: o pior caso é o comportamento v1, nunca uma rejeição a mais.
 */
@Component
public class ApplicabilityMarkerDetector {

    /** Versão do vocabulário de detecção — sobe sempre que os padrões mudam. */
    public static final String VOCABULARY_VERSION = "2026-10-02.3";

    private static final Map<ApplicabilityMarker, Pattern> PATTERNS;

    static {
        Map<ApplicabilityMarker, Pattern> patterns = new EnumMap<>(ApplicabilityMarker.class);
        // O papel de quem pergunta, não a mera menção: o senhorio fala do "seu inquilino".
        // "como inquilino" é papel; "como o inquilino deixou de pagar" (= visto que) não é.
        patterns.put(ApplicabilityMarker.INQUILINO, word(
                "(?:sou|enquanto|sendo|na qualidade de) (?:o |a |um |uma )?(?:inquilin|arrendatari)\\w*"
                        + "|como (?:inquilin|arrendatari)\\w*"
                        + "|(?:o|a|os|as) (?:inquilin|arrendatari)\\w* (?:pode|podem|deve|devem|consegue)\\w* "
                        + "(?:deduzir|abater|declarar|beneficiar)\\w*"
                        + "|(?:o|a|os|as) (?:inquilin|arrendatari)\\w* tem direito"));
        // "não é habitação própria" nega a situação
        patterns.put(ApplicabilityMarker.HABITACAO_PROPRIA, word(
                "(?<!\\bnao e )(?<!\\bnao sao )(?<!\\bnao seja )(?<!\\bnao e a minha )(?<!\\bnao e minha )"
                        + "(?:habitac(?:ao|oes) propria\\w*|casa propria|residencia propria)"));
        // só indivisão; "herdeiro" sozinho (herança já partilhada) não conta
        patterns.put(ApplicabilityMarker.HERANCA_INDIVISA, word("herancas? indivisas?|cabeca de casal"));
        // a origem da pensão, não a nacionalidade de quem a recebe
        patterns.put(ApplicabilityMarker.PENSAO_ESTRANGEIRA, word(
                "pens(?:ao|oes)(?: \\w+){0,4} estrangeir\\w*"));
        // "reclamar o reembolso" é pedir, não reclamar de um acto
        patterns.put(ApplicabilityMarker.RECLAMACAO, word("reclam(?!\\w* (?:o |um )?reembolso)\\w*|impugn\\w*"));
        // pagamento do imposto ou em prestações; não "prestações do crédito/sociais" nem "pagamentos por conta"
        patterns.put(ApplicabilityMarker.PAGAMENTO, word(
                "pagamento (?:do|da|de|em) (?:imposto|imi|aimi|irs|prestac\\w*)"
                        + "|pagar (?:o|a)(?: \\w+){0,2} (?:imposto|aimi|imi)"
                        + "|(?:pagar|pago|pagamos) em prestac\\w*|(?:em|por) prestac(?:ao|oes)\\b(?! de servic)"));
        patterns.put(ApplicabilityMarker.RETENCAO_OBRIGATORIA, word(
                "obrigad[oa]s? a(?: \\w+){0,2} (?:reter|retenc\\w*)|obrigac(?:ao|oes) de (?:reter|retenc\\w*)"
                        + "|retenc(?:ao|oes)(?: \\w+){0,2} obrigatori\\w*|obrigatori\\w*(?: \\w+){0,2} (?:reter|retenc\\w*)"
                        + "|tem (?:de|que) reter"));
        PATTERNS = Collections.unmodifiableMap(patterns);
    }

    /**
     * Marcadores presentes na pergunta. CALCULO reutiliza a operação já detectada pelo classificador
     * v1 (a mesma regra, incluindo a exclusão de "calculado em conjunto").
     */
    public Set<ApplicabilityMarker> detect(String question, FiscalScope queryScope) {
        String normalized = FiscalScopeClassifier.normalize(question);
        Set<ApplicabilityMarker> markers = EnumSet.noneOf(ApplicabilityMarker.class);
        PATTERNS.forEach((marker, pattern) -> {
            if (pattern.matcher(normalized).find()) {
                markers.add(marker);
            }
        });
        if (queryScope != null && queryScope.operations().contains(FiscalOperation.CALCULO)) {
            markers.add(ApplicabilityMarker.CALCULO);
        }
        return markers;
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
