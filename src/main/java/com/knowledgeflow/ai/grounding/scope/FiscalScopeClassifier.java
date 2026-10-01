package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.ai.grounding.scope.FiscalScope.FiscalOperation;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.IncomeCategory;
import com.knowledgeflow.ai.grounding.scope.FiscalScope.TaxDomain;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.text.Normalizer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Classificador determinístico de âmbito fiscal (M4-SCOPE): dicionário versionado de expressões,
 * sem modelos, sem serviços externos.
 *
 * <p>O texto é normalizado (minúsculas, sem acentos, pontuação como espaço) e procurado por
 * palavra inteira. "Adicional ao IMI"/AIMI é detectado e retirado antes de procurar IMI. Uma
 * categoria de rendimentos implica o domínio IRS, salvo em contexto de IRC ou de IVA das rendas. O
 * dicionário cobre termos gerais da língua e do domínio fiscal; não deve receber termos feitos à
 * medida de perguntas concretas.
 */
@Component
public class FiscalScopeClassifier {

    /** Versão do dicionário — sobe sempre que as expressões mudam (rastreabilidade nos logs/testes). */
    public static final String DICTIONARY_VERSION = "2026-10-01.4";

    private static final Pattern AIMI =
            word("aimi|adicional (?:ao|do|de) (?:imi|imposto municipal sobre (?:os )?imoveis)");

    /** Objectos típicos de uma obrigação de conservação documental. */
    private static final String DOCUMENTS =
            "(?:documentos?|registos?|fa[c]?turas?|livros|comprovativos?|recibos?(?! verdes?)|papeis)";
    /** Verbo ou substantivo ("conservação dos documentos"); "conservatória" fica de fora. */
    private static final String KEEP_WORD = "(?:conserv(?!atori)|guard|arquiv)\\w*";
    /** Só formas verbais: depois do objecto, "conservação" é a do bem ("faturas das obras de conservação"). */
    private static final String KEEP_VERB = "(?:conserv|guard|arquiv)(?:ar|ad[oa]s?|e|em|o|ei|amos)";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static final Map<TaxDomain, Pattern> DOMAINS;
    private static final Map<IncomeCategory, Pattern> CATEGORIES;
    private static final Map<FiscalOperation, Pattern> OPERATIONS;

    static {
        Map<TaxDomain, Pattern> domains = new LinkedHashMap<>();
        Map<IncomeCategory, Pattern> categories = new LinkedHashMap<>();
        Map<FiscalOperation, Pattern> operations = new LinkedHashMap<>();
        domains.put(TaxDomain.IMI, word("imi|imposto municipal sobre (?:os )?imoveis"));
        domains.put(TaxDomain.IMT, word("imt|imposto municipal sobre as transmissoes onerosas de imoveis"));
        domains.put(TaxDomain.IVA, word("iva|imposto sobre o valor acrescentado"));
        domains.put(TaxDomain.IRS, word("irs|imposto sobre o rendimento das pessoas singulares"));
        domains.put(TaxDomain.IRC, word("irc|imposto sobre o rendimento das pessoas colec?tivas"));

        // Categorias pelo tipo de rendimento, não pela pessoa ("reformado", "pensionista" descrevem
        // quem pergunta e não o rendimento em causa). "vencimento" fica de fora (também é "data de
        // vencimento"); "renda fixa/vitalícia" não são rendimentos prediais.
        categories.put(IncomeCategory.A, word(
                "categoria a(?! que)|salarios?|ordenados?|trabalho dependente|por conta de outrem"));
        categories.put(IncomeCategory.B, word(
                "categoria b(?! que)|trabalho independente|trabalhador(?:es)? independentes?|a[c]?tividade independente|recibos? verdes?"
                        + "|rendimentos empresariais|empresariais e profissionais"));
        categories.put(IncomeCategory.F, word(
                "categoria f(?! que)|rendas?(?! fixas?| vitalicias?)|arrend\\w*|prediais|inquilin\\w*|senhorio\\w*"));
        categories.put(IncomeCategory.G, word("categoria g(?! que)|mais valias?"));
        categories.put(IncomeCategory.H, word("categoria h(?! que)|pensao|pensoes"));

        operations.put(FiscalOperation.RETENCAO, word("retenc\\w*|reter|retid[oa]s?|retenha\\w*|retem"));
        operations.put(FiscalOperation.DEDUCAO, word("dedu\\w*|abat\\w*|despesas?|gastos?|encargos?"));
        // Conservação documental só com objecto documental (antes ou depois do verbo): "obras de
        // conservação" do imóvel e "guarda" de menores não são esta obrigação.
        operations.put(FiscalOperation.CONSERVACAO, word(
                KEEP_WORD + "(?: \\w+){0,3} " + DOCUMENTS + "|" + DOCUMENTS + "(?: \\w+){0,4} " + KEEP_VERB));
        operations.put(FiscalOperation.TRIBUTACAO_CONJUNTA,
                word("tributacao conjunta|tributad[oa]s? conjuntamente"));
        operations.put(FiscalOperation.CALCULO, word("calcul\\w*"));
        operations.put(FiscalOperation.DECLARACAO, word(
                "declaracao periodica|declaracao anual|declaracao de rendimentos|modelo 3"
                        + "|entrega da declaracao|entregar a declaracao"));
        operations.put(FiscalOperation.EMISSAO, word("emit\\w*|emissao"));

        DOMAINS = Collections.unmodifiableMap(domains);
        CATEGORIES = Collections.unmodifiableMap(categories);
        OPERATIONS = Collections.unmodifiableMap(operations);
    }

    /** Âmbito de uma pergunta (ou de qualquer texto livre). */
    public FiscalScope classify(String text) {
        String normalized = normalize(text);

        Set<TaxDomain> domains = EnumSet.noneOf(TaxDomain.class);
        String withoutAimi = normalized;
        if (AIMI.matcher(normalized).find()) {
            domains.add(TaxDomain.AIMI);
            withoutAimi = AIMI.matcher(normalized).replaceAll(" ");
        }
        for (Map.Entry<TaxDomain, Pattern> e : DOMAINS.entrySet()) {
            if (e.getValue().matcher(withoutAimi).find()) {
                domains.add(e.getKey());
            }
        }

        Set<IncomeCategory> categories = EnumSet.noneOf(IncomeCategory.class);
        CATEGORIES.forEach((category, pattern) -> {
            if (pattern.matcher(normalized).find()) {
                categories.add(category);
            }
        });
        boolean otherIncomeTaxContext = domains.contains(TaxDomain.IRC)
                || (domains.contains(TaxDomain.IVA) && categories.equals(EnumSet.of(IncomeCategory.F)));
        if (!categories.isEmpty() && !otherIncomeTaxContext) {
            // Categorias de rendimentos são do IRS, também ao lado de impostos sobre o património
            // (o IMI de um prédio arrendado é gasto da categoria F) ou de uma menção ao IVA de quem
            // passa recibos verdes. Não se acrescenta IRS quando a pergunta nomeia IRC, nem para
            // "IVA das rendas": aí as rendas são matéria desse imposto e a verificação de domínio
            // tem de continuar a valer.
            domains.add(TaxDomain.IRS);
        }

        Set<FiscalOperation> operations = EnumSet.noneOf(FiscalOperation.class);
        OPERATIONS.forEach((operation, pattern) -> {
            if (pattern.matcher(normalized).find()) {
                operations.add(operation);
            }
        });
        return new FiscalScope(domains, categories, operations);
    }

    /**
     * Âmbito de uma Q&amp;A candidata: o tema (quando designa um imposto) mais o subtema e a pergunta.
     * A resposta não é usada, para não importar termos de enquadramento como âmbito.
     */
    public FiscalScope classifyCandidate(KnowledgeTopic topic, String subtopic, String question) {
        FiscalScope fromText = classify(join(subtopic, question));
        Set<TaxDomain> domains = fromText.domains().isEmpty()
                ? EnumSet.noneOf(TaxDomain.class) : EnumSet.copyOf(fromText.domains());
        if (topic != null) {
            switch (topic) {
                case IRS -> domains.add(TaxDomain.IRS);
                case IVA -> domains.add(TaxDomain.IVA);
                case IRC -> domains.add(TaxDomain.IRC);
                default -> { } // PROCEDIMENTO_TRIBUTARIO, FATURACAO, … não determinam o imposto
            }
        }
        return new FiscalScope(domains, fromText.incomeCategories(), fromText.operations());
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(noAccents.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static String join(String a, String b) {
        return (a == null ? "" : a) + " " + (b == null ? "" : b);
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
