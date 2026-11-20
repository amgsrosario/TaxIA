package com.knowledgeflow.ai.spend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prova de cobertura da especificação KF-CONV-01 v13, §3.9, sobre o bytecode compilado da aplicação.
 * <p>
 * <b>O que demonstra.</b> Partindo de todas as classes de {@code com.knowledgeflow.research}, percorre transitivamente
 * todas as classes da aplicação que elas referenciam (tipos, campos, métodos, descritores, assinaturas genéricas e
 * constantes de texto), e também as implementações e subclasses de cada interface ou classe alcançada, porque o
 * Spring injecta implementações que o código não nomeia. Nenhuma classe alcançada pode referenciar o {@code AIService}
 * genérico, a sua implementação, os fornecedores ou o resolvedor de fornecedores, nem pelo nome interno nem por um
 * literal com o nome qualificado (por exemplo, {@code Class.forName("com.knowledgeflow.ai.DefaultAIService")}). O
 * único caminho autorizado é a porta {@link MeteredModelClient}, cujas implementações não são percorridas.
 * <p>
 * <b>O que não demonstra.</b> Não abrange nomes construídos em tempo de execução, nem a obtenção de beans por nome
 * ou por tipo dinâmico através do contexto Spring ({@code ApplicationContext}, {@code BeanFactory}). Por isso, também
 * se proíbe que qualquer classe alcançada (de {@code research} ou intermediária) use esses mecanismos,
 * {@code Class.forName} ou a reflexão de métodos. O {@code ObjectProvider<T>} é permitido: a injecção é por tipo, e o tipo
 * fica na assinatura genérica analisada, pelo que um {@code ObjectProvider<AIService>} seria detectado pelo nome. {@code java.lang.invoke} não é proibido, porque o compilador o usa em todas as lambdas e records; os
 * {@code MethodHandle} construídos à mão ficam fora da prova. Bibliotecas externas não são analisadas.
 */
class ResearchModelPortArchitectureTest {

    private static final String APP = "com/knowledgeflow/";
    private static final String PORT = "com/knowledgeflow/ai/spend/MeteredModelClient";

    /** Destinos proibidos (nomes internos exactos ou prefixos de pacote terminados em '/'). */
    private static final List<String> FORBIDDEN = List.of(
            "com/knowledgeflow/ai/AIService",
            "com/knowledgeflow/ai/DefaultAIService",
            "com/knowledgeflow/ai/AIProvider",
            "com/knowledgeflow/ai/AIProviderResolver",
            "com/knowledgeflow/ai/provider/");

    /** Mecanismos dinâmicos que nenhuma classe alcançada a partir de {@code research} pode usar. */
    private static final List<String> DYNAMIC_FORBIDDEN = List.of(
            "org/springframework/context/ApplicationContext",
            "org/springframework/beans/factory/BeanFactory",
            "org/springframework/beans/factory/ListableBeanFactory",
            "java/lang/reflect/Method");

    private static final Pattern INTERNAL = Pattern.compile("com/knowledgeflow/[A-Za-z0-9_/$]+");
    private static final Pattern DOTTED = Pattern.compile("com\\.knowledgeflow\\.[A-Za-z0-9_.$]+");

    /** Informação de uma classe compilada: super-tipos e todos os nomes referenciados na tabela de constantes. */
    record ClassInfo(String name, String superName, List<String> interfaces, Set<String> referenced, Set<String> utf8,
            Set<String> methodRefs) {}

    static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("não é um ficheiro de classe");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIndex = new int[count];
        int[][] memberRef = new int[count][];
        int[][] nameAndType = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 10, 11 -> memberRef[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 12 -> nameAndType[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 9, 17, 18 -> in.readInt();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("etiqueta desconhecida na tabela de constantes: " + tag);
            }
        }
        in.readUnsignedShort();
        String name = utf8[classIndex[in.readUnsignedShort()]];
        int superIdx = in.readUnsignedShort();
        String superName = superIdx == 0 ? null : utf8[classIndex[superIdx]];
        int n = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < n; i++) interfaces.add(utf8[classIndex[in.readUnsignedShort()]]);

        Set<String> referenced = new HashSet<>();
        Set<String> strings = new HashSet<>();
        for (String s : utf8) {
            if (s == null) continue;
            strings.add(s);
            Matcher m = INTERNAL.matcher(s);
            while (m.find()) referenced.add(m.group());
            Matcher d = DOTTED.matcher(s);
            while (d.find()) referenced.add(d.group().replace('.', '/'));
        }
        Set<String> methodRefs = new HashSet<>();
        for (int[] ref : memberRef) {
            if (ref == null) continue;
            methodRefs.add(utf8[classIndex[ref[0]]] + "." + utf8[nameAndType[ref[1]][0]]);
        }
        return new ClassInfo(name, superName, interfaces, referenced, strings, methodRefs);
    }

    static Map<String, ClassInfo> load(Path root) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                ClassInfo c = parse(Files.readAllBytes(f));
                classes.put(c.name(), c);
            }
        }
        return classes;
    }

    static boolean forbidden(String ref) {
        for (String f : FORBIDDEN) {
            if (f.endsWith("/") ? ref.startsWith(f) : ref.equals(f) || ref.startsWith(f + "$")) return true;
        }
        return false;
    }

    /**
     * Percurso transitivo a partir das classes cujo nome começa por {@code startPrefix}. Devolve as violações no
     * formato {@code caminho -> destino proibido}.
     */
    static List<String> violations(Map<String, ClassInfo> classes, String startPrefix) {
        Map<String, List<String>> subtypes = new HashMap<>();
        for (ClassInfo c : classes.values()) {
            if (c.superName() != null) subtypes.computeIfAbsent(c.superName(), k -> new ArrayList<>()).add(c.name());
            for (String i : c.interfaces()) subtypes.computeIfAbsent(i, k -> new ArrayList<>()).add(c.name());
        }
        Map<String, String> via = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String name : classes.keySet()) {
            if (name.startsWith(startPrefix)) {
                via.put(name, null);
                queue.add(name);
            }
        }
        Set<String> found = new TreeSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ClassInfo c = classes.get(current);
            for (String d : dynamicMechanisms(c)) found.add(path(via, current) + " -> " + d);
            List<String> next = new ArrayList<>(c.referenced());
            // A porta é a fronteira autorizada: as suas implementações não são percorridas.
            if (!current.equals(PORT)) next.addAll(subtypes.getOrDefault(current, List.of()));
            for (String ref : next) {
                if (forbidden(ref)) {
                    found.add(path(via, current) + " -> " + ref);
                    continue;
                }
                if (classes.containsKey(ref) && !via.containsKey(ref)) {
                    via.put(ref, current);
                    queue.add(ref);
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Mecanismos dinâmicos usados pela classe: obtenção de beans, {@code Class.forName} e reflexão de métodos. */
    static List<String> dynamicMechanisms(ClassInfo c) {
        List<String> out = new ArrayList<>();
        for (String f : DYNAMIC_FORBIDDEN) {
            if (c.utf8().stream().anyMatch(s -> s.contains(f))) out.add("dinâmico:" + f);
        }
        if (c.methodRefs().contains("java/lang/Class.forName")) out.add("dinâmico:Class.forName");
        return out;
    }

    private static String path(Map<String, String> via, String last) {
        List<String> chain = new ArrayList<>();
        for (String c = last; c != null; c = via.get(c)) chain.add(0, c.substring(c.lastIndexOf('/') + 1));
        return String.join(" > ", chain);
    }

    private static Path appClasses() throws Exception {
        return Path.of(MeteredModelClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void researchReachesNoModelCallOutsideThePort() throws Exception {
        Map<String, ClassInfo> classes = load(appClasses());
        assertThat(classes.keySet()).anyMatch(n -> n.startsWith(APP + "research/"));
        assertThat(violations(classes, APP + "research/")).isEmpty();
    }

    @Test
    void researchModelComponentsDependOnThePort() throws Exception {
        Map<String, ClassInfo> classes = load(appClasses().resolve("com/knowledgeflow/research/service"));
        for (String component : List.of("ModelDiscoveryHypothesisProvider", "ModelPropositionExtractor",
                "ResearchAnswerComposer")) {
            assertThat(classes.get(APP + "research/service/" + component).referenced()).as(component).contains(PORT);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Controlo do próprio detector, com classes compiladas do teste (pacote fictício de ensaio).
    // ---------------------------------------------------------------------------------------------

    @Test
    void detectorFindsDirectIndirectInjectedAndReflectiveReferences() throws Exception {
        Map<String, ClassInfo> classes = load(appClasses());
        String base = "com/knowledgeflow/ai/spend/ResearchModelPortArchitectureTest$";
        Map<String, ClassInfo> probe = new LinkedHashMap<>(classes);
        probe.putAll(load(Path.of(ResearchModelPortArchitectureTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).resolve("com/knowledgeflow/ai/spend")));
        // A classe de teste exterior referencia todas as sondas; fica fora do grafo de ensaio.
        probe.remove("com/knowledgeflow/ai/spend/ResearchModelPortArchitectureTest");
        assertThat(violations(probe, base + "ProbeDirect")).singleElement().asString()
                .endsWith("-> com/knowledgeflow/ai/AIService");
        assertThat(violations(probe, base + "ProbeIndirect")).singleElement().asString()
                .contains("ProbeIndirect > ResearchModelPortArchitectureTest$Intermediary")
                .endsWith("-> com/knowledgeflow/ai/AIService");
        assertThat(violations(probe, base + "ProbeInjected")).isNotEmpty().allSatisfy(v -> assertThat(v)
                .contains("ProbeInjected > ResearchModelPortArchitectureTest$Gateway > ResearchModelPortArchitectureTest$GatewayImpl"));
        assertThat(violations(probe, base + "ProbeReflective")).containsExactlyInAnyOrder(
                "ResearchModelPortArchitectureTest$ProbeReflective -> com/knowledgeflow/ai/DefaultAIService",
                "ResearchModelPortArchitectureTest$ProbeReflective -> dinâmico:Class.forName");
        assertThat(violations(probe, base + "ProbeViaPort")).isEmpty();
        assertThat(violations(probe, base + "ProbeObjectProvider")).singleElement().asString()
                .endsWith("-> com/knowledgeflow/ai/AIService");
        assertThat(violations(probe, base + "ProbeBeanLookup")).singleElement().asString()
                .contains("ProbeBeanLookup > ResearchModelPortArchitectureTest$BeanLookupIntermediary")
                .endsWith("-> dinâmico:org/springframework/context/ApplicationContext");
        assertThat(probe.get(base + "ProbeReflective").methodRefs()).contains("java/lang/Class.forName");
        assertThat(probe.get(base + "ProbeDirect").methodRefs()).doesNotContain("java/lang/Class.forName");
    }

    @Test
    void parserReadsClassReferencesOfARealClass(@TempDir Path dir) throws Exception {
        ClassInfo c = parse(Files.readAllBytes(appClasses().resolve(
                "com/knowledgeflow/research/service/ResearchAnswerComposer.class")));
        assertThat(c.name()).isEqualTo(APP + "research/service/ResearchAnswerComposer");
        assertThat(c.referenced()).contains(PORT, "com/knowledgeflow/ai/AIRequest");
        assertThat(forbidden("com/knowledgeflow/ai/AIServiceHelper")).isFalse();
        assertThat(forbidden("com/knowledgeflow/ai/AIProvider")).isTrue();
        assertThat(forbidden("com/knowledgeflow/ai/provider/stub/StubAIProvider")).isTrue();
    }

    // Sondas de ensaio: nunca são instanciadas; só o seu bytecode é analisado.

    static class ProbeDirect {
        com.knowledgeflow.ai.AIService service;
    }

    static class Intermediary {
        Object call(com.knowledgeflow.ai.AIService s) {
            return s;
        }
    }

    static class ProbeIndirect {
        Intermediary intermediary;
    }

    interface Gateway {
        void call();
    }

    static class GatewayImpl implements Gateway {
        com.knowledgeflow.ai.AIProviderResolver resolver;

        @Override
        public void call() {
            resolver.resolve();
        }
    }

    static class ProbeInjected {
        Gateway gateway;
    }

    static class ProbeReflective {
        Object load() throws Exception {
            return Class.forName("com.knowledgeflow.ai.DefaultAIService");
        }
    }

    /** Intermediário que obtém um bean pelo nome, sem nomear o tipo proibido. */
    static class BeanLookupIntermediary {
        org.springframework.context.ApplicationContext context;

        Object model() {
            return context.getBean("defaultAIService");
        }
    }

    static class ProbeBeanLookup {
        BeanLookupIntermediary intermediary;
    }

    static class ObjectProviderIntermediary {
        org.springframework.beans.factory.ObjectProvider<com.knowledgeflow.ai.AIService> provider;
    }

    static class ProbeObjectProvider {
        ObjectProviderIntermediary intermediary;
    }

    static class ProbeViaPort {
        MeteredModelClient port;
    }
}
