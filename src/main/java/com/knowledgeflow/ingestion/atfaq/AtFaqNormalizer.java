package com.knowledgeflow.ingestion.atfaq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Normalization for RAW AT FAQ content.
 * <p>
 * Goal: a whitespace-stable representation so the content hash detects real
 * changes only. The text is NEVER rewritten legally: numbers, article
 * references, rates, deadlines, monetary values and Portuguese accents are
 * preserved exactly as extracted.
 */
@Component
public class AtFaqNormalizer {

    /**
     * Canonical form: Unicode NFC, LF line endings, single spaces inside lines,
     * lines trimmed, at most one blank line between paragraphs.
     * Content (words, digits, symbols, accents) is untouched.
     */
    public String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace('\u00A0', ' '); // non-breaking spaces count as plain spaces
        StringBuilder out = new StringBuilder(s.length());
        int blankRun = 0;
        for (String line : s.split("\n", -1)) {
            String trimmed = line.strip().replaceAll("[ \\t]+", " ");
            if (trimmed.isEmpty()) {
                blankRun++;
                continue;
            }
            if (out.length() > 0) {
                out.append(blankRun > 0 ? "\n\n" : "\n");
            }
            out.append(trimmed);
            blankRun = 0;
        }
        return out.toString();
    }

    /**
     * SHA-256 hash over the stable representation of question + answer.
     * Whitespace-only differences never change the hash; any semantic edit does.
     */
    public String contentHash(String question, String answer) {
        String stable = normalize(question) + "\n␞\n" + normalize(answer);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(stable.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Hash of a full page body — used by the page snapshot cache. */
    public String pageHash(String body) {
        return contentHash("page", body == null ? "" : body);
    }
}
