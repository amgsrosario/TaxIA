package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Todos os testes de integração constroem o contentor PostgreSQL pelo {@link PgContainers}, que espera pelo log e pela
 * porta mapeada. Uma construção directa volta a esperar só pelo log, que foi a causa dos «Connection refused»
 * corrigida no PR #30.
 * <p>
 * O detector ignora comentários e literais de texto e reconhece {@code new PostgreSQLContainer}, com ou sem nome de
 * pacote, com espaços ou mudanças de linha antes de {@code <} ou {@code (}, genérico ou raw. Só são excluídos os dois
 * caminhos concretos autorizados.
 */
class PgContainersUsageTest {

    static final Set<String> ALLOWED = Set.of(
            "com/knowledgeflow/pgtest/PgContainers.java",
            "com/knowledgeflow/pgtest/PgContainersUsageTest.java");

    private static final Pattern DIRECT = Pattern.compile(
            "\\bnew\\s+(?:org\\s*\\.\\s*testcontainers\\s*\\.\\s*containers\\s*\\.\\s*)?PostgreSQLContainer\\s*[<(]");

    /** Remove comentários ({@code //}, {@code /* *\/}) e o conteúdo de literais de texto, carácter e text blocks. */
    static String code(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) j += src.charAt(j) == '\\' ? 2 : 1;
                i = Math.min(n, j + 1);
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static List<String> directConstructions(Path testSources) throws IOException {
        try (Stream<Path> files = Files.walk(testSources)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(testSources.relativize(p).toString().replace('\\', '/')))
                    .filter(p -> {
                        try {
                            return DIRECT.matcher(code(Files.readString(p))).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> testSources.relativize(p).toString().replace('\\', '/'))
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
    void detectorFindsEveryFormOfDirectConstruction(@TempDir Path dir) throws IOException {
        write(dir, "A.java", "class A { Object c = new PostgreSQLContainer<>(\"img\"); }");
        write(dir, "B.java", "class B { Object c = new org.testcontainers.containers.PostgreSQLContainer<>(\"img\"); }");
        write(dir, "C.java", "class C { Object c = new PostgreSQLContainer\n    <>(\"img\"); }");
        write(dir, "D.java", "class D { Object c = new PostgreSQLContainer(\"img\"); }");
        write(dir, "E.java", "class E { Object c = new   PostgreSQLContainer <PostgreSQLContainer<?>>(\"img\"); }");
        write(dir, "outro/PgContainers.java", "class PgContainers { Object c = new PostgreSQLContainer<>(\"img\"); }");
        assertThat(directConstructions(dir)).containsExactly("A.java", "B.java", "C.java", "D.java", "E.java",
                "outro/PgContainers.java");
    }

    @Test
    void detectorIgnoresCommentsStringsAndTheHelper(@TempDir Path dir) throws IOException {
        write(dir, "F.java", "class F { // new PostgreSQLContainer<>(\"x\")\n Object c = PgContainers.pgvector(); }");
        write(dir, "G.java", "/** Não usar {@code new PostgreSQLContainer<>(...)}. */ class G {}");
        write(dir, "H.java", "class H { String s = \"new PostgreSQLContainer<>(x)\"; String t = \"\"\"\n new PostgreSQLContainer<>()\n\"\"\"; }");
        write(dir, "I.java", "class I { Object c = new PostgreSQLContainerFactory(); }");
        write(dir, "com/knowledgeflow/pgtest/PgContainers.java", "class PgContainers { Object c = new PostgreSQLContainer<>(\"img\"); }");
        assertThat(directConstructions(dir)).isEmpty();
    }

    private static void write(Path dir, String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
