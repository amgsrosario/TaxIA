package com.knowledgeflow.ai.spend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Prova de cobertura da especificação KF-CONV-01 v13, §3.9: todas as chamadas ao modelo do pacote {@code research}
 * passam por {@link MeteredModelClient}. Nenhuma classe compilada do pacote referencia o {@code AIService} genérico,
 * os fornecedores, o resolvedor de fornecedores ou a implementação concreta da porta.
 * <p>
 * A verificação é feita sobre o bytecode (tabela de constantes), por isso apanha também nomes qualificados, tipos de
 * campos e de parâmetros e chamadas de métodos, e ignora comentários.
 */
class ResearchModelPortArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/knowledgeflow/ai/AIService",
            "com/knowledgeflow/ai/DefaultAIService",
            "com/knowledgeflow/ai/AIProvider",
            "com/knowledgeflow/ai/AIProviderResolver",
            "com/knowledgeflow/ai/provider/",
            "com/knowledgeflow/ai/spend/UnfundedModelClient");

    private static Path researchClasses() throws URISyntaxException {
        Path port = Path.of(MeteredModelClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return port.resolve("com/knowledgeflow/research");
    }

    static List<String> violations(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                // Os nomes internos ficam na tabela de constantes em UTF-8 modificado, igual a ASCII para estes nomes.
                String bytes = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                for (String name : FORBIDDEN) {
                    int i = bytes.indexOf(name);
                    while (i >= 0) {
                        int end = i + name.length();
                        // O nome tem de acabar aqui: um nome mais comprido (AIServiceHelper) não conta. Depois do nome vem
                        // ';' (descritor), '$' (classe interna) ou o byte seguinte da tabela de constantes. Para pacotes,
                        // basta o prefixo terminado em '/'.
                        char next = end < bytes.length() ? bytes.charAt(end) : ';';
                        if (name.endsWith("/") || !isNameChar(next)) {
                            found.add(root.relativize(f) + " -> " + name);
                            break;
                        }
                        i = bytes.indexOf(name, i + 1);
                    }
                }
            }
        }
        return found;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    @Test
    void researchPackageUsesOnlyTheMeteredPort() throws Exception {
        Path root = researchClasses();
        assertThat(root).isDirectory();
        assertThat(violations(root)).isEmpty();
    }

    @Test
    void researchModelComponentsDependOnThePort() throws Exception {
        Path root = researchClasses().resolve("service");
        for (String component : List.of("ModelDiscoveryHypothesisProvider", "ModelPropositionExtractor",
                "ResearchAnswerComposer")) {
            String bytes = new String(Files.readAllBytes(root.resolve(component + ".class")), StandardCharsets.ISO_8859_1);
            assertThat(bytes).as(component).contains("com/knowledgeflow/ai/spend/MeteredModelClient");
        }
    }

    @Test
    void scannerDetectsAForbiddenReference(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // Controlo do próprio teste: um ficheiro com a referência proibida é detectado; um nome vizinho não o é.
        Files.write(dir.resolve("A.class"), "xxLcom/knowledgeflow/ai/AIService;yy".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(dir.resolve("B.class"), "xxcom/knowledgeflow/ai/AIServiceHelperyy".getBytes(StandardCharsets.ISO_8859_1));
        // Referência de classe na tabela de constantes: o nome seguido directamente do byte de etiqueta seguinte (0x01).
        Files.write(dir.resolve("C.class"), "\u0001com/knowledgeflow/ai/AIProviderResolver\u0001".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(violations(dir)).containsExactlyInAnyOrder("A.class -> com/knowledgeflow/ai/AIService",
                "C.class -> com/knowledgeflow/ai/AIProviderResolver");
    }
}
