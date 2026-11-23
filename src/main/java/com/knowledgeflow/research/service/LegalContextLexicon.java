package com.knowledgeflow.research.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Vocabulário jurídico português usado pela verificação do contexto normativo (KF-PILOT/R-2). É conhecimento de domínio
 * (TaxIA), separado da lógica genérica de {@link NormativeContextGuard}. É necessariamente incompleto: recusa formas conhecidas e
 * não demonstra a preservação do sentido.
 */
final class LegalContextLexicon {

    private LegalContextLexicon() {}

    /** Negações, excepções, condições e efeitos capazes de alterar o sentido da disposição. Texto normalizado, em minúsculas. */
    static final Pattern MARKER = Pattern.compile("(?<![\\p{L}])(não|nem|nunca|salvo|exceto|excepto|exceção|excepção|desde que|sem prejuízo"
            + "|ressalvad\\p{L}*|com exclusão|excluíd\\p{L}*|excluem\\p{L}*|apenas se|só se|unicamente se"
            + "|a menos que|sempre que|na condição|contanto que|fora d[aeo]s?|tributad\\p{L}*|sujeit\\p{L}* a[os]?|sujeit\\p{L}* à|mediante|após|depois de|antes de|até)(?![\\p{L}])");

    /** Efeitos fiscais, por radical sem acentos: quem os afirma tem de os encontrar na unidade normativa. Cada grupo conta como um efeito. */
    static final List<List<String>> EFFECTS = List.of(List.of("isent", "isenc"), List.of("dedut", "deduz", "deduc"), List.of("tribut"),
            List.of("sujeit"), List.of("pagam", "pagar", "pago", "paga"));

    /** Palavras do enunciado que não indicam a matéria: não bastam para ligar um enunciado a uma afirmação. */
    static final Set<String> GENERIC = Set.of("seguintes", "seguinte", "operacoes", "operacao", "termos", "artigo", "artigos", "subsequentes",
            "estao", "sendo", "numero", "alinea", "alineas", "relativas", "relativos", "casos", "presente", "disposto", "podem", "devem", "ficam", "ser",
            "realizadas", "efetuadas", "efectuadas");
}
