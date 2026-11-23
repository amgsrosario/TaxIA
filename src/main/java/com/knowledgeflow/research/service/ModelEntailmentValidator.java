package com.knowledgeflow.research.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.ai.AITaskType;
import com.knowledgeflow.research.source.ResearchProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validador de implicação jurídica pelo modelo (KF-M02/ARCH-01). Uma chamada por proposição, com a
 * proposição, a unidade legal e o texto oficial do artigo; resposta em JSON com veredicto, as sete
 * dimensões, justificação e passagem citada. A leitura é estrita ({@link #parse}): exactamente um
 * objecto JSON, sem texto à volta, sem chaves duplicadas ou desconhecidas e com vocabulário fechado; o
 * resto são erros de protocolo — e a decisão ({@link EntailmentGate}) trata-os como suporte não demonstrado.
 * <p>
 * Formato (KF-M02/CORR-09): o pedido leva o {@link #outputSchema()} (structured outputs — a resposta é
 * gerada já restringida ao schema). O schema não substitui a leitura estrita, que se mantém: uma resposta
 * truncada ({@code max_tokens}) ou recusada ({@code refusal}) é erro de protocolo antes de qualquer leitura,
 * e uma resposta fora do protocolo fica com um diagnóstico só da forma ({@link ResponseDiagnostics}).
 * <p>
 * Desligado por omissão ({@code knowledgeflow.research.entailment.enabled=false}).
 */
@Component
public class ModelEntailmentValidator implements EntailmentValidator {

    private static final Logger log = LoggerFactory.getLogger(ModelEntailmentValidator.class);

    public static final String SYSTEM_PROMPT = """
            Tarefa de VALIDAÇÃO DE IMPLICAÇÃO JURÍDICA. Não é redacção de resposta.
            Decide se o TEXTO LEGAL fornecido implica, por si só, a PROPOSIÇÃO — não se ela é plausível,
            habitual ou verdadeira segundo o teu conhecimento. Não uses memória própria nem outras normas.
            Avalia expressamente cada dimensão, comparando a proposição com o texto legal:
            - sujeito: a quem se aplica (a mesma pessoa, entidade ou categoria; sem trocar sujeito e objecto;
              sem alargar a categorias vizinhas não referidas);
            - predicado: o efeito jurídico afirmado (o mesmo efeito; sem antónimos nem efeitos diferentes);
            - polaridade: afirmação/negação idênticas;
            - condicoes: todas as condições do texto (incluindo as cumulativas) estão na proposição ou nas
              suas condições; nenhuma inventada;
            - excepcoes: as excepções do texto, também noutros números do mesmo artigo, estão consideradas;
              nenhuma inventada;
            - restricoes: restrições de âmbito ("apenas", "só", limites) preservadas, com o MESMO objecto: o número,
              alínea ou categoria a que o texto aplica a restrição. Aplicar a todo o artigo, ao imposto ou a
              outras deduções uma restrição que o texto limita a um número é NAO_CONFORME; nenhuma inventada;
            - ambito_temporal: vigência, revogação, datas e períodos compatíveis. Uma marca de revogação sem
              data nem diploma revogatório prova só o estado actual do texto: uma proposição que conclua sobre
              aplicabilidade, efeitos ou períodos anteriores excede o texto (NAO_CONFORME). Uma conclusão
              temporal nunca é CONFORME se o texto não a documenta.
            Estado de cada dimensão: CONFORME, NAO_CONFORME, NAO_APLICAVEL ou INDETERMINADO.
            Veredicto: SUPORTA só se todas as dimensões forem CONFORME ou NAO_APLICAVEL; NAO_SUPORTA se
            alguma for NAO_CONFORME; INDETERMINADO em caso de dúvida. Na dúvida, nunca SUPORTA.
            Em "passagem" copia LITERALMENTE, sem alterar palavras nem pontuação, o excerto do texto legal
            em que te baseias, de dentro da UNIDADE LEGAL indicada.
            Responde exclusivamente com UM objecto JSON, sem texto antes ou depois:
            {"veredicto":"SUPORTA|NAO_SUPORTA|INDETERMINADO",
             "dimensoes":{"sujeito":{"estado":"...","nota":"..."},"predicado":{...},"polaridade":{...},
                          "condicoes":{...},"excepcoes":{...},"restricoes":{...},"ambito_temporal":{...}},
             "passagem":"...","justificacao":"..."}
            """;

    private static final Map<String, Dimension> KEYS = Map.of(
            "sujeito", Dimension.SUJEITO, "predicado", Dimension.PREDICADO, "polaridade", Dimension.POLARIDADE,
            "condicoes", Dimension.CONDICOES, "excepcoes", Dimension.EXCEPCOES, "restricoes", Dimension.RESTRICOES,
            "ambito_temporal", Dimension.AMBITO_TEMPORAL);

    /**
     * JSON Schema da resposta, derivado dos próprios vocabulários ({@link Verdict}, {@link DimensionStatus},
     * {@link #KEYS}): todos os campos obrigatórios, nenhum campo adicional, as sete dimensões presentes.
     */
    private static final ObjectNode OUTPUT_SCHEMA = buildOutputSchema();

    /** Cópia do schema enviado ao modelo. */
    public static ObjectNode outputSchema() {
        return OUTPUT_SCHEMA.deepCopy();
    }

    private static ObjectNode buildOutputSchema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode dimension = object(f);
        ((ObjectNode) dimension.get("properties")).set("estado", enumString(f, DimensionStatus.values()));
        ((ObjectNode) dimension.get("properties")).set("nota", f.objectNode().put("type", "string"));
        dimension.set("required", f.arrayNode().add("estado").add("nota"));

        ObjectNode dimensions = object(f);
        ArrayNode dimensionsRequired = f.arrayNode();
        KEYS.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> {
            ((ObjectNode) dimensions.get("properties")).set(e.getKey(), dimension.deepCopy());
            dimensionsRequired.add(e.getKey());
        });
        dimensions.set("required", dimensionsRequired);

        ObjectNode root = object(f);
        ObjectNode props = (ObjectNode) root.get("properties");
        props.set("veredicto", enumString(f, Verdict.values()));
        props.set("dimensoes", dimensions);
        props.set("passagem", f.objectNode().put("type", "string"));
        props.set("justificacao", f.objectNode().put("type", "string"));
        root.set("required", f.arrayNode().add("veredicto").add("dimensoes").add("passagem").add("justificacao"));
        return root;
    }

    private static ObjectNode object(JsonNodeFactory f) {
        ObjectNode o = f.objectNode().put("type", "object");
        o.set("properties", f.objectNode());
        o.put("additionalProperties", false);
        return o;
    }

    private static ObjectNode enumString(JsonNodeFactory f, Enum<?>[] values) {
        ArrayNode names = f.arrayNode();
        for (Enum<?> v : values) names.add(v.name());
        ObjectNode n = f.objectNode().put("type", "string");
        n.set("enum", names);
        return n;
    }

    private final MeteredModelClient aiService;
    private final ObjectMapper strictMapper;
    private final ModelIdentity modelIdentity;
    private final ResearchProperties properties;

    public ModelEntailmentValidator(MeteredModelClient aiService, ObjectMapper objectMapper, ModelIdentity modelIdentity,
            ResearchProperties properties) {
        this.aiService = aiService;
        this.strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.modelIdentity = modelIdentity;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.getEntailment().isEnabled();
    }

    @Override
    public Assessment assess(Request request) {
        if (!isAvailable()) return Assessment.protocolFailure("validador desligado", null);
        AIResponse response;
        try {
            response = aiService.complete(new AIRequest(AITaskType.REVIEW, SYSTEM_PROMPT, userPrompt(request), outputSchema()));
        } catch (RuntimeException e) {
            log.warn("Validação de implicação indisponível: {}", e.getClass().getSimpleName());
            return Assessment.protocolFailure("falha da chamada ao modelo", ModelCall.failed(modelIdentity.current(), e));
        }
        String diagnostic = ResponseDiagnostics.of(response.content(), response.stopReason());
        java.util.function.Function<String, ModelCall> unparseable = detail -> {
            log.warn("Validação de implicação fora do protocolo: {} | {}", detail, diagnostic);
            return ModelCall.unparseable(response, detail + " | " + diagnostic);
        };
        if ("max_tokens".equals(response.stopReason())) return failure("resposta truncada (max_tokens)", unparseable);
        if ("refusal".equals(response.stopReason())) return failure("recusa do modelo (refusal)", unparseable);
        return parse(strictMapper, response.content(), ModelCall.completed(response), unparseable);
    }

    public static String userPrompt(Request r) {
        StringBuilder sb = new StringBuilder();
        sb.append("PERGUNTA (contexto):\n").append(r.question()).append("\n\n");
        sb.append("PROPOSIÇÃO:\n").append(r.statement()).append('\n');
        if (r.qualifiers() != null && !r.qualifiers().isEmpty()) {
            sb.append("Condições/excepções declaradas da proposição:\n");
            r.qualifiers().forEach(q -> sb.append("- ").append(q.kind()).append(": ").append(q.description()).append('\n'));
        }
        sb.append("\nUNIDADE LEGAL (").append(r.legalReference()).append("):\n").append(r.legalUnit()).append("\n\n");
        sb.append("TEXTO LEGAL COMPLETO DO ARTIGO (").append(r.legalReference()).append("):\n").append(r.evidenceContent()).append('\n');
        return sb.toString();
    }

    private static final java.util.Set<String> TOP_LEVEL = java.util.Set.of("veredicto", "dimensoes", "passagem", "justificacao");
    private static final java.util.Set<String> DIMENSION_FIELDS = java.util.Set.of("estado", "nota");

    /**
     * Leitura estrita (KF-M02/CORR-05): a resposta tem de ser EXACTAMENTE um objecto JSON válido — só
     * espaço em branco à volta; qualquer texto antes ou depois (incluindo blocos de código) é erro de
     * protocolo. Também são erros: chaves duplicadas, conteúdo depois do objecto, chaves desconhecidas,
     * tipos errados e valores fora do vocabulário. Dimensões em falta são tratadas pelo {@link EntailmentGate}.
     */
    static Assessment parse(ObjectMapper strictMapper, String text, ModelCall completed,
            java.util.function.Function<String, ModelCall> unparseable) {
        if (text == null || text.isBlank()) return failure("resposta vazia", unparseable);
        String body = text.strip();
        if (!body.startsWith("{") || !body.endsWith("}")) return failure("texto fora do objecto JSON", unparseable);
        JsonNode root;
        try {
            root = strictMapper.readTree(body);
        } catch (Exception e) {
            return failure("JSON inválido, com chaves duplicadas ou com mais de um objecto", unparseable);
        }
        if (root == null || !root.isObject()) return failure("a resposta não é um objecto JSON", unparseable);
        for (var it = root.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!TOP_LEVEL.contains(k)) return failure("chave desconhecida: " + k, unparseable);
        }
        JsonNode v = root.get("veredicto");
        if (v == null || v.isNull()) return failure("sem veredicto", unparseable);
        if (!v.isTextual()) return failure("veredicto inválido", unparseable);
        Verdict verdict = enumValue(Verdict.class, v.asText());
        if (verdict == null) return failure("veredicto inválido", unparseable);
        for (String f : List.of("passagem", "justificacao")) {
            JsonNode n = root.get(f);
            if (n != null && !n.isNull() && !n.isTextual()) return failure("campo " + f + " com tipo inválido", unparseable);
        }

        Map<Dimension, DimensionStatus> dims = new EnumMap<>(Dimension.class);
        Map<Dimension, String> notes = new EnumMap<>(Dimension.class);
        JsonNode d = root.get("dimensoes");
        if (d != null && !d.isNull()) {
            if (!d.isObject()) return failure("dimensoes não é um objecto", unparseable);
            for (var it = d.fields(); it.hasNext(); ) {
                var e = it.next();
                Dimension dim = KEYS.get(e.getKey());
                if (dim == null) return failure("dimensão desconhecida: " + e.getKey(), unparseable);
                JsonNode node = e.getValue();
                if (!node.isObject()) return failure("dimensão " + e.getKey() + " não é um objecto", unparseable);
                for (var fit = node.fieldNames(); fit.hasNext(); ) {
                    String f = fit.next();
                    if (!DIMENSION_FIELDS.contains(f)) return failure("campo desconhecido em " + e.getKey() + ": " + f, unparseable);
                }
                JsonNode estado = node.get("estado");
                DimensionStatus st = estado != null && estado.isTextual() ? enumValue(DimensionStatus.class, estado.asText()) : null;
                if (st == null) return failure("estado inválido na dimensão " + e.getKey(), unparseable);
                JsonNode nota = node.get("nota");
                if (nota != null && !nota.isNull() && !nota.isTextual()) return failure("nota inválida em " + e.getKey(), unparseable);
                dims.put(dim, st);
                if (nota != null && nota.isTextual() && !nota.asText().isBlank()) notes.put(dim, nota.asText().strip());
            }
        }
        return new Assessment(verdict, dims, notes, ModelJson.text(root, "justificacao"), ModelJson.text(root, "passagem"),
                null, completed);
    }

    private static Assessment failure(String error, java.util.function.Function<String, ModelCall> unparseable) {
        return Assessment.protocolFailure(error, unparseable.apply("Validação fora do protocolo: " + error));
    }

    /** Valor exacto do vocabulário (sem normalização): qualquer outra grafia é inválida. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
        for (E e : type.getEnumConstants()) if (e.name().equals(raw)) return e;
        return null;
    }
}
