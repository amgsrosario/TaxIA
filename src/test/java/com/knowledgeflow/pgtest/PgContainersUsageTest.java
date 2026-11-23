package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Todos os testes de integração constroem o contentor PostgreSQL pelo {@link PgContainers}, que espera pelo log e pela
 * porta mapeada. Uma construção directa ({@code new PostgreSQLContainer<>(...)}) volta a esperar só pelo log, que foi
 * a causa dos «Connection refused» corrigida no PR #30.
 */
class PgContainersUsageTest {

    static List<String> directConstructions(Path testSources) throws IOException {
        try (Stream<Path> files = Files.walk(testSources)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("PgContainers.java"))
                    .filter(p -> !p.getFileName().toString().equals("PgContainersUsageTest.java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("new PostgreSQLContainer<");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> testSources.relativize(p).toString())
                    .sorted()
                    .toList();
        }
    }

    @Test
    void integrationTestsUseTheSharedWaitStrategy() throws IOException {
        Path testSources = Path.of("src/test/java");
        assertThat(testSources).isDirectory();
        assertThat(directConstructions(testSources)).isEmpty();
    }

    @Test
    void detectorFindsADirectConstruction(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("XIT.java"), "class XIT { Object c = new PostgreSQLContainer<>(\"img\"); }");
        Files.writeString(dir.resolve("YIT.java"), "class YIT { Object c = PgContainers.pgvector(); }");
        assertThat(directConstructions(dir)).containsExactly("XIT.java");
    }
}
