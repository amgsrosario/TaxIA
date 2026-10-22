package com.knowledgeflow.research;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Páginas oficiais reais do Portal das Finanças, obtidas por consulta read-only em 2026-10-22 e
 * gravadas em {@code src/test/resources/research/}. Só os tokens de formulário do SharePoint
 * (__VIEWSTATE, __EVENTVALIDATION, form digest) foram substituídos por {@code REDACTED}; o texto
 * oficial não foi alterado.
 */
public final class ResearchFixtures {

    public static final String CIRS_78 = "cirs-art78-2026-10-22.html";
    public static final String CIVA_9 = "civa-art9-2026-10-22.html";

    private ResearchFixtures() {
    }

    public static String page(String name) {
        try (InputStream in = ResearchFixtures.class.getResourceAsStream("/research/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
