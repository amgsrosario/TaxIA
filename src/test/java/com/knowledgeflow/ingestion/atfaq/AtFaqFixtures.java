package com.knowledgeflow.ingestion.atfaq;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the fictional HTML fixtures used by the AT FAQ tests. */
public final class AtFaqFixtures {

    private AtFaqFixtures() {
    }

    public static String load(String name) {
        String resource = "/at-faq/" + name;
        try (InputStream in = AtFaqFixtures.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the index fixture with the BASE_URL placeholder resolved. */
    public static String loadIndex(String baseUrl) {
        return load("index.html").replace("BASE_URL", baseUrl);
    }
}
