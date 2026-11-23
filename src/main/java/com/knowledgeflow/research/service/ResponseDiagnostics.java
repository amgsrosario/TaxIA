package com.knowledgeflow.research.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Diagnóstico seguro de uma resposta do modelo fora do protocolo (KF-M02/CORR-09): só a FORMA da
 * resposta, nunca o conteúdo — comprimento, motivo de paragem, como começa, posição do objecto JSON,
 * texto antes e depois, cercas de código, equilíbrio das chavetas e um prefixo do SHA-256 para
 * correlação. Nenhuma palavra da resposta é copiada: a resposta pode repetir a pergunta ou o texto
 * legal, e {@link ModelCall} nunca guarda respostas.
 */
public final class ResponseDiagnostics {

    private ResponseDiagnostics() {}

    public static String of(String text, String stopReason) {
        String reason = stopReason == null ? "—" : sanitize(stopReason);
        if (text == null || text.isEmpty()) return "diagnóstico: comprimento=0; stop_reason=" + reason;
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        String stripped = text.strip();
        return "diagnóstico: comprimento=" + text.length()
                + "; stop_reason=" + reason
                + "; início=" + start(stripped)
                + "; primeiro_{=" + open
                + "; último_}=" + close
                + "; antes_do_objecto=" + (open < 0 ? text.strip().length() : text.substring(0, open).strip().length())
                + "; depois_do_objecto=" + (close < 0 ? 0 : text.substring(close + 1).strip().length())
                + "; cercas=" + count(text, "```")
                + "; chavetas=" + balance(text)
                + "; sha256=" + sha256Prefix(text);
    }

    private static String start(String stripped) {
        if (stripped.isEmpty()) return "VAZIO";
        if (stripped.startsWith("```")) return "CERCA";
        if (stripped.startsWith("{")) return "OBJECTO";
        if (stripped.startsWith("[")) return "LISTA";
        return "TEXTO";
    }

    private static int count(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) n++;
        return n;
    }

    /** Chavetas fora de strings JSON: EQUILIBRADAS, ABERTAS (resposta truncada) ou EXCESSO. */
    private static String balance(String text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) return "EXCESSO";
            }
        }
        if (inString) return "ABERTAS(string por fechar)";
        return depth == 0 ? "EQUILIBRADAS" : "ABERTAS(" + depth + ")";
    }

    private static String sha256Prefix(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "indisponível";
        }
    }

    /** O motivo de paragem vem do fornecedor: só um identificador curto. */
    private static String sanitize(String value) {
        String v = value.replaceAll("[^A-Za-z0-9_\\-]", "");
        return v.length() > 32 ? v.substring(0, 32) : v;
    }
}
