package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
    public static final String VOCABULARY_VERSION = "2026-10-15.4";

    /** Sujeitos de uma operação que podem ser qualificados como não residentes. */
    private static final String ENTITY = "(?:sujeitos? passivos?|empresas?|entidades?|sociedades?)";
    /**
     * Prova explícita de não residência. "estrangeiro" não basta: pode ser origem, nacionalidade ou
     * grupo, e não exclui sede ou residência em Portugal.
     */
    private static final String NON_RESIDENT = "(?:nao residentes?"
            + "|sem (?:residencia|sede|domicilio|estabelecimento estavel)(?: \\w+){0,2}? em portugal"
            + "|(?:com|tem|tinha|possui|possuem) (?:a )?sede (?:fora de portugal|no estrangeiro)"
            + "|estabelecid[oa]s? fora de portugal)";
    /**
     * Ligações curtas entre o sujeito e a qualidade ("o cliente e tambem nao residente"). Limitado a
     * três: uma repetição ilimitada recursa sobre texto arbitrário (StackOverflowError).
     */
    private static final String LINK = "(?: (?:e|for|seja|sao|sendo|forem|sejam|tambem|igualmente|ambos|ambas)){0,3}";
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

    /**
     * Marcadores de exclusão cuja menção negada não conta ("nao tenho actividade aberta", "sem
     * actividade", "nao estou colectado"): a negação descreve precisamente o caso oposto.
     */
    private static final Set<ApplicabilityMarker> LOCALLY_NEGATABLE = EnumSet.of(ApplicabilityMarker.ACTIVIDADE_REGULAR);
    /** Fronteiras de oração (inclui a vírgula: "Nao, tenho actividade regular"). */
    private static final Pattern CLAUSE = Pattern.compile("[.;:!?,]+");
    private static final int LOCAL_NEGATION_TOKENS = 3;
    /** Papel de quem adquire. */
    private static final String ACQUIRER = "(?:adquirente|cliente|destinatario|comprador)(?:e?s)?";
    /** Papel de quem fornece. */
    private static final String SUPPLIER = "(?:fornecedor(?:es)?|prestador(?:es)?|vendedor(?:es)?|transmitentes?)";
    /**
     * Um qualificativo entre o papel e o predicado ("o cliente estrangeiro tem sede…"), nunca uma
     * negação nem uma preposição que inverta o sentido ("sem sede", "fora de").
     */
    private static final String QUALIFIER = "(?: (?!nao\\b|nem\\b|sem\\b|fora\\b|nunca\\b)\\w+)?";
    /** Residência ou sede em Portugal, ligada ao papel que a antecede. */
    private static final String RESIDENT_HERE = "(?:residentes?(?: em portugal| no territorio nacional)?"
            + "|portugues(?:a|es|as)?|nacional|nacionais"
            + "|(?:(?:com|tem|tinha|possui|possuem|dispoe de) (?:a |uma |um )?)?"
            + "(?:sede|residencia|domicilio|estabelecimento estavel)(?: \\w+){0,2}? em portugal)";
    /**
     * Residência em Portugal de quem fornece (sinal interno, não é marcador): anula
     * FORNECEDOR_NAO_RESIDENTE ("fornecedor nacional", "o fornecedor estrangeiro tem sede em Portugal").
     */
    private static final Pattern SUPPLIER_RESIDENT_HERE =
            Pattern.compile("\\b(?:" + SUPPLIER + QUALIFIER + "(?: (?:e|for|seja|sendo))? " + RESIDENT_HERE + ")\\b");
    /** Fornecedor e adquirente nomeados juntos, em qualquer ordem ("o fornecedor e o cliente"). */
    private static final String BOTH_ROLES =
            "(?:" + SUPPLIER + QUALIFIER + " e (?:os |as |o |a )?" + ACQUIRER + "|" + ACQUIRER + QUALIFIER + " e (?:os |as |o |a )?" + SUPPLIER + ")"
            + QUALIFIER;
    /**
     * Predicado colectivo inequívoco depois dos dois papéis ("sao", "sao ambos", "ambos"). Sem ele, uma
     * qualificação singular ("o fornecedor e o cliente nao residente") fica só no papel que a
     * antecede. Limitado a duas palavras: nunca uma repetição ilimitada (sem risco de pilha).
     */
    private static final String COLLECTIVE_PREDICATE =
            "(?: (?:sao|estao|forem|sejam|ficam|serao)(?: (?:ambos|ambas|tambem|igualmente))?| (?:ambos|ambas))";
    /** Verbo plural de posse aplicado aos dois papéis ("tem ambos sede…"). */
    private static final String COLLECTIVE_HAVE = " (?:tem|possuem|tinham)(?: (?:ambos|ambas))? (?:a |uma |um )?";
    /** Os dois papéis com residência/sede em Portugal, por predicado colectivo. */
    private static final String BOTH_RESIDENT = BOTH_ROLES
            + "(?:" + COLLECTIVE_PREDICATE + " " + RESIDENT_HERE
            + "|" + COLLECTIVE_HAVE + "(?:sede|residencia|domicilio|estabelecimento estavel)(?: \\w+){0,2}? em portugal)";
    private static final Pattern BOTH_RESIDENT_HERE = Pattern.compile("\\b(?:" + BOTH_RESIDENT + ")\\b");
    /**
     * Negação da cópula ou da posse ("nao sao residentes", "nem o fornecedor nem…", "nao tem sede"):
     * para o contexto obrigatório anula os marcadores de não residência. "nao residente" é uma
     * qualidade e não conta.
     */
    private static final Pattern RESIDENCY_NEGATION = Pattern.compile(
            "\\b(?:nem|nunca|jamais|nao (?:e|sao|era|eram|for|forem|seja|sejam|esta|estao|tem|tinha|possui|possuem))\\b");
    private static final Set<ApplicabilityMarker> NON_RESIDENCY =
            EnumSet.of(ApplicabilityMarker.FORNECEDOR_NAO_RESIDENTE, ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE);

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
    /**
     * Os dois papéis, nomeados, são não residentes por predicado colectivo ("fornecedor e adquirente
     * sao ambos nao residentes", "ambos nao residentes", "tem sede fora de Portugal"). Formas
     * colectivas sem papéis ("ambos", "dois clientes", "duas empresas") e qualificações singulares
     * depois do segundo papel ("o fornecedor e o cliente nao residente") não contam para os dois.
     */
    private static final String BOTH_NON_RESIDENT = BOTH_ROLES
            + "(?:" + COLLECTIVE_PREDICATE + " " + NON_RESIDENT
            + "|" + COLLECTIVE_HAVE + "sede (?:fora de portugal|no estrangeiro))";

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
                        + "|" + SUPPLIER + LINK + " " + NON_RESIDENT
                        + "|" + ENTITY + " " + NON_RESIDENT + " (?:que (?:nos |me |lhe |lhes )?)?" + SUPPLY_VERB
                        + "|por (?:um|uma) (?:" + ENTITY + "|fornecedor|prestador) " + NON_RESIDENT));
        // Quem adquire é não residente: pelo papel ("cliente nao residente") ou por "a outra empresa
        // estrangeira" depois de um verbo de fornecimento ("presta um servico ... a outra empresa
        // estrangeira"). Sem esse verbo, "a" pode ser "de quem se compra" ("compro a outra empresa
        // estrangeira") e não conta.
        patterns.put(ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE, word(
                BOTH_NON_RESIDENT
                        + "|" + ACQUIRER + LINK + " " + NON_RESIDENT
                        // janela curta e sem atravessar outra oração ("... e eu compro a outra empresa")
                        + "|" + SALE_VERB + "(?: (?!e\\b|mas\\b|tambem\\b|eu\\b|compr|adquir)\\w+){0,6}? a outr[oa]s? "
                        + ENTITY + "(?: tambem)? " + NON_RESIDENT
                        // "por um sujeito passivo nao residente a outro sujeito passivo nao residente"
                        + "|por (?:um|uma) " + ENTITY + " " + NON_RESIDENT + " a outr[oa]s? " + ENTITY + " " + NON_RESIDENT));
        // Defesa híbrida (só exclusão, C9-A): actividade regular de quem pergunta.
        patterns.put(ApplicabilityMarker.ACTIVIDADE_REGULAR, word(
                "(?:actividade|atividade)s? (?:aberta|regular|habitual|registada|declarada)s?"
                        + "|exercicio habitual"
                        + "|(?:tenho|temos|exerco|exercemos) (?:uma |a )?(?:actividade|atividade)"
                        + "|(?:sou|estou|somos|estamos|fiquei|ficamos) (?:colectad|coletad)\\w*"));
        // Defesa híbrida (só exclusão, C9-A): o adquirente é residente em Portugal. Pelo papel
        // ("cliente portugues", "o adquirente tem estabelecimento estavel em Portugal", "o cliente e uma
        // empresa portuguesa") ou por "empresa portuguesa compra". "Portugal" sozinho não conta.
        // AIMI pelo nome: "AIMI", "adicional ao/do IMI", "adicional ao imposto municipal sobre imoveis".
        // Nunca por "tributacao conjunta", "imoveis", "IMI" isolado ou "prazo".
        patterns.put(ApplicabilityMarker.AIMI, word(
                "aimi|adicional (?:ao|do) imi|adicional (?:ao|do) imposto municipal sobre (?:os )?imoveis"));
        // Renúncia ou cessação da opção (só exclusão): "renunciar", "desfazer a opcao", "cancelar/cessar/
        // revogar/anular/terminar/retirar a opcao", "deixar de optar", "desistir da opcao".
        patterns.put(ApplicabilityMarker.CESSACAO_OPCAO, word(
                "renunci\\w*|desfaz\\w*|desfeit[oa]s?"
                        + "|(?:cancel|cess|revog|anul|termin|retir)\\w* (?:a |da |com a )?opc(?:ao|oes)"
                        + "|deix\\w* de (?:optar|ter a opcao)|desist\\w* da opcao"));
        // Alteração do estado civil (só exclusão): divórcio, separação de pessoas, fim da união de facto.
        // "separacao de bens" (regime de bens), "separadamente" e coisas separadas não contam.
        patterns.put(ApplicabilityMarker.ALTERACAO_ESTADO_CIVIL, word(
                "divorci\\w*"
                        // separação de pessoas, não de coisas: "separei-me", "estamos separados",
                        // "separacao de facto/judicial"; nunca "separacao de bens" nem "predios separados"
                        + "|separei|separamo\\w*|separaram(?: se)?|separou se"
                        + "|(?:sou|somos|estou|estamos|fiquei|ficamos|ficou|ficaram) separad[oa]s?"
                        + "|separacao (?:de facto|judicial|de pessoas|do casal)|separad[oa]s? (?:de facto|judicialmente)"
                        + "|(?:fim|cessacao|termo|dissolucao|rutura|ruptura) da uniao de facto"
                        + "|(?:terminou|acabou|cessou|terminamos|acabamos)(?: \\w+){0,2} uniao de facto"
                        + "|deix\\w* de viver em uniao de facto"));
        patterns.put(ApplicabilityMarker.ADQUIRENTE_RESIDENTE, word(
                ACQUIRER + QUALIFIER + LINK + " " + RESIDENT_HERE
                        + "|" + BOTH_RESIDENT
                        + "|" + ACQUIRER + LINK + " (?:uma |um )?" + ENTITY + " (?:portugues(?:a|es|as)?|residentes? em portugal)"
                        + "|" + ENTITY + " portugues(?:a|es|as)? (?:que )?(?:compra|compram|adquire|adquirem|contrata|contratam)"));
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
            boolean found = LOCALLY_NEGATABLE.contains(marker)
                    ? hasLocallyAffirmedOccurrence(pattern, question)
                    : pattern.matcher(normalized).find();
            if (found) {
                markers.add(marker);
            }
        });
        if (queryScope != null && queryScope.operations().contains(FiscalOperation.CALCULO)) {
            markers.add(ApplicabilityMarker.CALCULO);
        }
        // Residência explícita em Portugal prevalece sobre a não residência do mesmo papel.
        if (BOTH_RESIDENT_HERE.matcher(normalized).find()) {
            markers.removeAll(NON_RESIDENCY);
        }
        if (SUPPLIER_RESIDENT_HERE.matcher(normalized).find()) {
            markers.remove(ApplicabilityMarker.FORNECEDOR_NAO_RESIDENTE);
        }
        if (markers.contains(ApplicabilityMarker.ADQUIRENTE_RESIDENTE)) {
            markers.remove(ApplicabilityMarker.ADQUIRENTE_NAO_RESIDENTE);
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
        String normalized = FiscalScopeClassifier.normalize(question);
        if (affirmed.stream().anyMatch(NEGATABLE::contains) && NEGATION_WORD.matcher(normalized).find()) {
            affirmed.removeAll(NEGATABLE);
        }
        if (affirmed.stream().anyMatch(NON_RESIDENCY::contains) && RESIDENCY_NEGATION.matcher(normalized).find()) {
            affirmed.removeAll(NON_RESIDENCY);
        }
        return affirmed;
    }

    /**
     * Alguma ocorrência sem negação nas {@link #LOCAL_NEGATION_TOKENS} palavras anteriores da mesma
     * oração. Para exclusões: na dúvida (negada) não dispara e fica o comportamento anterior.
     */
    private static boolean hasLocallyAffirmedOccurrence(Pattern pattern, String question) {
        for (String clause : CLAUSE.split(question == null ? "" : question)) {
            String normalized = FiscalScopeClassifier.normalize(clause);
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String before = normalized.substring(0, matcher.start()).trim();
                String[] tokens = before.isEmpty() ? new String[0] : before.split(" ");
                String window = String.join(" ",
                        Arrays.copyOfRange(tokens, Math.max(0, tokens.length - LOCAL_NEGATION_TOKENS), tokens.length));
                if (!NEGATION_WORD.matcher(window).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
