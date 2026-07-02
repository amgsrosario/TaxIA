package com.knowledgeflow.knowledge.service;

import java.text.Normalizer;
import org.springframework.stereotype.Component;

/**
 * Lightweight text normalizer for Q&A import.
 * Preserves the original content; only applied to derived fields.
 */
@Component
public class KnowledgeQuestionAnswerNormalizer {

    /** Normalize a question: trim, collapse whitespace, ensure no trailing period. */
    public String normalizeQuestion(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\r\\n|\\r|\\n", " ");
        s = s.replaceAll("[ \\t]{2,}", " ");
        // Remove trailing period (questions don't end with '.')
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.strip();
    }

    /** Normalize an answer: trim, collapse blank lines to at most one. */
    public String normalizeAnswer(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("([ \\t]*\\r?\\n){3,}", "\n\n");
        return s.strip();
    }

    /** Normalize to a canonical comparison key (lowercase, NFC Unicode, compressed spaces). */
    public String toComparisonKey(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFC);
        s = s.toLowerCase();
        s = s.replaceAll("\\s+", " ");
        return s.strip();
    }
}
