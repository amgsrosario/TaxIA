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
    public static final String VOCABULARY_VERSION = "2026-10-14.2";

    /** Sujeitos de uma operação que podem ser qualificados como não residentes. */
    private static final String ENTITY = "(?:sujeitos? passivos?|empresas?|entidades?|sociedades?)";
    private static final String NON_RESIDENT = "(?:nao residentes?|estrangeir\\w*)";
    /**
     * Ligações curtas entre o sujeito e a qualidade ("o cliente e tambem nao residente"). Limitado a
     * três: uma repetição ilimitada recursa sobre texto arbitrário (StackOverflowError).
     */
    private static final String LINK = "(?: (?:e|for|seja|sao|sendo|forem|sejam|tambem|igualmente)){0,3}";
    /**
     * Marcadores cuja ocorrência pode ser negada na pergunta ("nao e um acto isolado", "o senhorio nao
     * autoriza a sublocacao"). Para exclusões conta a simples presença (na dúvida exclui: falha
     * segura). Para o contexto obrigatório (ADR-009) só conta uma ocorrência afirmada — sem negação
     * na mesma oração antes dela — porque um falso positivo aceitaria o caso oposto.
     */
    private static final Set<ApplicabilityMarker> NEGATABLE =
            EnumSet.of(ApplicabilityMarker.ACTO_ISOLADO, ApplicabilityMarker.SUBLOCACAO);
    /**
     * Palavras de negação ("nao", "nunca", "sem", "deixou de", "proibe", "impede", "recusa", "fora de
     * questao", "nenhum"). Usadas só para o contexto obrigatório (ADR-009): se aparecerem em qualquer
     * ponto da pergunta, os marcadores negáveis não contam como afirmados. "nao residente" é uma
     * qualidade, não uma negação.
     */
    private static final Pattern NEGATION_WORD = Pattern.compile(
            "\\b(?:nao(?! residentes?\\b)|nunca|jamais|sem|nenhum\\w*|deix\\w* de|proib\\w*|imped\\w*|recus\\w*|fora de questao)\\b");

    /** Verbos de quem fornece, na 3.ª pessoa ("presta", "vendem"); "prestamos"/"efectua aquisicoes" não contam. */
    private static final String SUPPLY_VERB = "(?:presta|prestam|vende|vendem|fornece|fornecem|transmite|transmitem)";
    /**
     * Formas verbais de quem fornece a um terceiro ("presto ... a outra empresa"). Só verbos: os
     * radicais apanhariam substantivos ("prestacao", "fornecedor", "transmissao").
     */
    private static final String SALE_VERB = "(?:presto|prestamos|presta|prestam|prestei|prestou|prestaram"
            + "|vendo|vendemos|vende|vendem|vendi|vendeu|venderam"
            + "|forneco|fornecemos|fornece|fornecem|forneci|forneceu|forneceram"
            + "|transmito|transmitimos|transmite|transmitem|transmiti|transmitiu|transmitiram)";
    /** Os dois lados da operação são não residentes ("ambos nao residentes", "duas empresas estrangeiras"). */
    private static final String BOTH_NON_RESIDENT =
            "ambos(?: os)?" + LINK + " " + NON_RESIDENT
            + "|(?:dois|duas|entre)(?: " + ENTITY + ")? " + NON_RESIDENT;

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
        // ADR-009 (C9-A). Subarrendamento/sublocação: regime próprio (CIRS art. 41.º, n.º 6).
        patterns.put(ApplicabilityMarker.SUBLOCACAO, word(
                "(?:subloca\\w*|subarrend\\w*|sublocatari\\w*)"));
        // Acto isolado de IVA; a negação ("nao e um acto isolado") não conta.
        patterns.put(ApplicabilityMarker.ACTO_ISOLADO, word(
                "(?:(?:acto|ato)s? isolados?|operac(?:ao|oes) isoladas?|uma so operacao tributavel)"));
        // Quem fornece é não residente: pelo papel ("fornecedor nao residente"), pela acção na 3.ª
        // pessoa ("empresa estrangeira (que) presta") ou por "por um sujeito passivo nao residente".
        // "empresa estrangeira a quem prestamos" é o adquirente e não conta.
        patterns.put(ApplicabilityMarker.FORNECEDOR_NAO_RESIDENTE, word(
                BOTH_NON_RESIDENT
                        + "|(?:fornecedor(?:es)?|prestador(?:es)?|vendedor(?:es)?|transmitentes?)" + LINK + " " + NON_RESIDENT
                        + "|" + ENTITY + " " + NON_RESIDENT + " (?:que (?:nos |me |lhe |lhes )?)?" + SUPPLY_VERB
                        + "|por (?:um|uma) (?:" + ENTITY + "|fornecedor|prestador) " + NON_RESIDENT));
        // Quem adquire é não residente: pelo papel ("cliente nao residente") ou por "a outra empresa
        // estrangeira" depois de um verbo de fornecimento ("presta um servico ... a outra empresa
        // estrangeira"). Sem esse verbo, "a" pode ser "de quem se compra" ("compro a outra empresa
        // estrangeira") e não conta.
        patterns.put(ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE, word(
                BOTH_NON_RESIDENT
                        + "|(?:adquirente|cliente|destinatario|comprador)(?:e?s)?" + LINK + " " + NON_RESIDENT
                        // janela curta e sem atravessar outra oração ("... e eu compro a outra empresa")
                        + "|" + SALE_VERB + "(?: (?!e\\b|mas\\b|tambem\\b|eu\\b|compr|adquir)\\w+){0,6}? a outr[oa]s? "
                        + ENTITY + "(?: tambem)? " + NON_RESIDENT
                        // "por um sujeito passivo nao residente a outro sujeito passivo nao residente"
                        + "|por (?:um|uma) " + ENTITY + " " + NON_RESIDENT + " a outr[oa]s? " + ENTITY + " " + NON_RESIDENT));
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

    /**
     * Marcadores afirmados, para o contexto obrigatório (ADR-009): os presentes, menos os negáveis
     * quando a pergunta contém uma negação em qualquer ponto. Antes, depois ou noutra frase, a negação
     * pode recair sobre o marcador ("a sublocacao nao e permitida", "Acto isolado? Nao, tenho
     * actividade"); não se tenta adivinhar o alcance. O pior caso é a rejeição do candidato
     * (resposta-limite), nunca a aceitação do caso oposto.
     */
    public Set<ApplicabilityMarker> detectAffirmed(String question, FiscalScope queryScope) {
        Set<ApplicabilityMarker> affirmed = detect(question, queryScope);
        if (affirmed.stream().anyMatch(NEGATABLE::contains)
                && NEGATION_WORD.matcher(FiscalScopeClassifier.normalize(question)).find()) {
            affirmed.removeAll(NEGATABLE);
        }
        return affirmed;
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
