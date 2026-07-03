package com.knowledgeflow.ingestion.atfaq;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Conservative detection of legal references in FAQ answers.
 * <p>
 * Only records literal patterns actually present in the text (articles,
 * paragraphs, subparagraphs, codes, statutes, list items/verbas). It never
 * invents, completes or legally validates a reference — output is raw
 * evidence for the curator.
 */
@Component
public class AtFaqLegalReferenceExtractor {

    private static final List<Pattern> PATTERNS = List.of(
            // artigo 21.º / artigos 19.º e 20.º / art. 53º / artigo 18.º-A
            Pattern.compile("(?i)\\bartigos?\\s+\\d+\\.?\\s?[ºo°]?(?:-[A-Z])?"),
            Pattern.compile("(?i)\\bart\\.?\\s*\\d+\\.?\\s?[ºo°]?(?:-[A-Z])?"),
            // n.º 1 / n.ºs 1 e 2 / nº 3
            Pattern.compile("(?i)\\bn\\.?\\s?[ºo°]s?\\s*\\d+"),
            // alínea a) / alíneas a) e b)
            Pattern.compile("(?i)\\balíneas?\\s+[a-z]\\)"),
            // Codes: CIVA, CIRC, CIRS, LGT, CPPT, RITI
            Pattern.compile("\\b(CIVA|CIRC|CIRS|LGT|CPPT|RITI)\\b"),
            // Código do IVA / Código do IRC / Código do IRS
            Pattern.compile("(?i)\\bCódigo do (IVA|IRC|IRS|Imposto[\\p{L} ]{0,40})"),
            // Decreto-Lei n.º 102/2008 / Lei n.º 82-B/2014 / Portaria n.º 195/2020
            Pattern.compile("(?i)\\b(Decreto-Lei|Lei|Portaria|Despacho|Ofício-Circulado)\\s+n\\.?\\s?[ºo°]\\s*\\d+(?:[-/][A-Z0-9]+)*(?:/\\d{2,4})?"),
            // verba 2.36 da Lista I
            Pattern.compile("(?i)\\bverbas?\\s+\\d+(?:\\.\\d+)*"),
            Pattern.compile("(?i)\\bLista\\s+(I{1,3}|IV)\\b"));

    /** Returns distinct references in order of first appearance. */
    public List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                found.add(matcher.group().strip());
            }
        }
        return new ArrayList<>(found);
    }
}
