package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.ai.AITaskType;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.enums.StepOutcome;
import com.knowledgeflow.research.service.EntailmentValidator.Assessment;
import com.knowledgeflow.research.service.EntailmentValidator.Dimension;
import com.knowledgeflow.research.service.EntailmentValidator.DimensionStatus;
import com.knowledgeflow.research.service.EntailmentValidator.Request;
import com.knowledgeflow.research.service.EntailmentValidator.Verdict;
import com.knowledgeflow.research.source.ConsolidatedArticleParser;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.ResearchProperties;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Protocolo da segunda barreira (KF-M02/ARCH-01), com respostas SIMULADAS do modelo: leitura estrita
 * da resposta ({@link ModelEntailmentValidator}) e decisão determinística ({@link EntailmentGate}).
 * Ausência de veredicto, contradição, erro ou incerteza = suporte não demonstrado.
 */
class EntailmentProtocolTest {

    /** Igual a {@code ResearchTestModel.allDimensions} do piloto; local porque esse helper depende de A4. */
    private static Map<Dimension, DimensionStatus> allDimensions(DimensionStatus status) {
        Map<Dimension, DimensionStatus> out = new EnumMap<>(Dimension.class);
        for (Dimension d : Dimension.values()) out.put(d, status);
        return out;
    }


    static final String N5_ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";
    static final String STATEMENT = "As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português";

    final MeteredModelClient ai = mock(MeteredModelClient.class);
    final ResearchProperties properties = new ResearchProperties();
    final EntailmentGate gate = new EntailmentGate();
    ModelEntailmentValidator validator;
    String art78;
    String unit;

    @BeforeEach
    void setUp() {
        properties.getEntailment().setEnabled(true);
        AIProperties aiProperties = mock(AIProperties.class);
        validator = new ModelEntailmentValidator(ai, new ObjectMapper(), new ModelIdentity(aiProperties), properties);
        art78 = new ConsolidatedArticleParser().parse(ResearchFixtures.page(ResearchFixtures.CIRS_78),
                new LegislationReference("CIRS", 78, null), LocalDate.of(2026, 10, 23)).content();
        unit = SupportVerifier.unitContext(N5_ANCHOR, art78);
    }

    static String json(String verdict, String dimensionState, String passage) {
        StringBuilder d = new StringBuilder();
        for (String k : List.of("sujeito", "predicado", "polaridade", "condicoes", "excepcoes", "restricoes", "ambito_temporal")) {
            if (d.length() > 0) d.append(',');
            d.append('"').append(k).append("\":{\"estado\":\"").append(dimensionState).append("\",\"nota\":\"n\"}");
        }
        return "{\"veredicto\":\"" + verdict + "\",\"dimensoes\":{" + d + "},\"passagem\":\"" + passage
                + "\",\"justificacao\":\"O n.º 5 limita as deduções aos residentes.\"}";
    }

    private Request request() {
        return new Request("Um não residente pode deduzir?", STATEMENT, List.of(), "artigo 78.º do CIRS", unit, art78);
    }

    private EntailmentGate.Decision run(String modelText) {
        when(ai.complete(any())).thenReturn(new AIResponse("anthropic", "claude-haiku-synthetic", modelText, 1800, 250, 900));
        Assessment a = validator.assess(request());
        return gate.decide(a, STATEMENT, art78, unit);
    }

    @Test
    @DisplayName("SUPORTA, sete dimensões conformes, passagem literal da unidade → DEMONSTRATED; pedido completo e sem memória crítica")
    void wellFormedSupportIsDemonstrated() {
        EntailmentGate.Decision d = run(json("SUPORTA", "CONFORME", N5_ANCHOR));
        assertThat(d.demonstrated()).as(d.reason()).isTrue();
        assertThat(d.citationVerified()).isTrue();

        ArgumentCaptor<AIRequest> sent = ArgumentCaptor.forClass(AIRequest.class);
        verify(ai).complete(sent.capture());
        assertThat(sent.getValue().taskType()).isEqualTo(AITaskType.REVIEW);
        assertThat(sent.getValue().userMessage()).contains(STATEMENT).contains("UNIDADE LEGAL").contains(N5_ANCHOR)
                .contains("6 - As deduções referidas nas alíneas a) a i)"); // texto completo: excepções noutros números
        assertThat(sent.getValue().systemPrompt()).contains("sujeito").contains("polaridade").contains("ambito_temporal")
                .doesNotContain("EVIDÊNCIA OFICIAL NÃO PROMOVIDA");
    }

    /** Resposta da barreira com a dimensão temporal num estado dado e as restantes conformes. */
    static String temporal(String verdict, String temporalState, String passage) {
        return json(verdict, "CONFORME", passage).replace(
                "\"ambito_temporal\":{\"estado\":\"CONFORME\"", "\"ambito_temporal\":{\"estado\":\"" + temporalState + "\"");
    }

    private EntailmentGate.Decision revoked(String statement, String modelText) {
        String revokedUnit = SupportVerifier.unitContext("4 - (Revogado.)", art78);
        when(ai.complete(any())).thenReturn(new AIResponse("anthropic", "claude-haiku-synthetic", modelText, 1800, 250, 900));
        Assessment a = validator.assess(new Request("O n.º 4 do artigo 78.º do CIRS ainda se aplica?", statement, List.of(),
                "artigo 78.º do CIRS", revokedUnit, art78));
        return gate.decide(a, statement, art78, revokedUnit);
    }

    @Test
    @DisplayName("CORR-11, A4 real: AMBITO_TEMPORAL=NAO_CONFORME → não demonstrado; o prompt define a marca de revogação como estado actual")
    void a4TemporalScopeNonConformingIsNotDemonstrated() {
        EntailmentGate.Decision d = revoked("O n.º 4 do artigo 78.º do CIRS está revogado",
                temporal("NAO_SUPORTA", "NAO_CONFORME", "4 - (Revogado.)"));
        assertThat(d.demonstrated()).isFalse();
        assertThat(ModelEntailmentValidator.SYSTEM_PROMPT.replaceAll("\\s+", " "))
                .contains("Uma marca de revogação sem data nem diploma revogatório prova só o estado actual do texto")
                .contains("aplicabilidade, efeitos ou períodos anteriores excede o texto (NAO_CONFORME)")
                .contains("Uma conclusão temporal nunca é CONFORME se o texto não a documenta");
    }

    @Test
    @DisplayName("CORR-11: numa unidade revogada, SUPORTA com AMBITO_TEMPORAL=NAO_APLICAVEL não demonstra; CONFORME demonstra")
    void revokedUnitRequiresConformingTemporalScope() {
        String statement = "O n.º 4 do artigo 78.º do CIRS está revogado";
        EntailmentGate.Decision notApplicable = revoked(statement, temporal("SUPORTA", "NAO_APLICAVEL", "4 - (Revogado.)"));
        assertThat(notApplicable.demonstrated()).isFalse();
        assertThat(notApplicable.reason()).contains("AMBITO_TEMPORAL");
        EntailmentGate.Decision conforming = revoked(statement, temporal("SUPORTA", "CONFORME", "4 - (Revogado.)"));
        assertThat(conforming.demonstrated()).as(conforming.reason()).isTrue();
        // Fora de uma unidade revogada, NAO_APLICAVEL no âmbito temporal continua admitido (comportamento inalterado).
        assertThat(run(temporal("SUPORTA", "NAO_APLICAVEL", N5_ANCHOR)).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("POST-REAL07: a dimensão restricoes exige o mesmo objecto da restrição (número, alínea ou categoria), não só «apenas»")
    void restrictionDimensionRequiresSameObject() {
        assertThat(ModelEntailmentValidator.SYSTEM_PROMPT.replaceAll("\\s+", " "))
                .contains("restricoes: restrições de âmbito (\"apenas\", \"só\", limites) preservadas, com o MESMO objecto")
                .contains("Aplicar a todo o artigo, ao imposto ou a outras deduções uma restrição que o texto limita a um número é NAO_CONFORME");
    }

    @Test
    @DisplayName("Barreira desligada: nenhuma chamada ao modelo e suporte não demonstrado")
    void disabledMakesNoCall() {
        properties.getEntailment().setEnabled(false);
        assertThat(validator.isAvailable()).isFalse();
        Assessment a = validator.assess(request());
        assertThat(gate.decide(a, STATEMENT, art78, unit).demonstrated()).isFalse();
        verify(ai, never()).complete(any());
    }

    @Test
    @DisplayName("NAO_SUPORTA e INDETERMINADO → não demonstrado")
    void negativeAndUncertainVerdicts() {
        assertThat(run(json("NAO_SUPORTA", "CONFORME", N5_ANCHOR)).reason()).contains("NAO_SUPORTA");
        assertThat(run(json("INDETERMINADO", "CONFORME", N5_ANCHOR)).reason()).contains("INDETERMINADO");
    }

    @Test
    @DisplayName("Ausência de veredicto ou de protocolo: sem JSON, sem veredicto, veredicto inválido → não demonstrado")
    void missingVerdict() {
        assertThat(run("A norma suporta claramente a proposição.").demonstrated()).isFalse();
        assertThat(run(json("SUPORTA", "CONFORME", N5_ANCHOR).replace("\"veredicto\":\"SUPORTA\",", "")).reason())
                .contains("sem veredicto");
        assertThat(run(json("PROVAVELMENTE", "CONFORME", N5_ANCHOR)).reason()).contains("veredicto inválido");
        assertThat(run("{\"veredicto\": \"SUPORTA\", ").demonstrated()).isFalse();
    }

    @Test
    @DisplayName("Contradição: SUPORTA com dimensão NAO_CONFORME; veredictos duplicados; dois objectos; veredicto fora do JSON")
    void contradictions() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        assertThat(run(good.replace("\"polaridade\":{\"estado\":\"CONFORME\"", "\"polaridade\":{\"estado\":\"NAO_CONFORME\"")).reason())
                .contains("contradição").contains("POLARIDADE");
        assertThat(run(good.replace("{\"veredicto\":\"SUPORTA\",", "{\"veredicto\":\"NAO_SUPORTA\",\"veredicto\":\"SUPORTA\",")).reason())
                .contains("fora do protocolo");
        assertThat(run(good + "\n" + json("NAO_SUPORTA", "NAO_CONFORME", N5_ANCHOR)).demonstrated()).isFalse();
        assertThat(run("VEREDICTO: NAO_SUPORTA\n" + good).reason()).contains("texto fora do objecto JSON");
    }

    @Test
    @DisplayName("Incerteza ou omissão nas dimensões: INDETERMINADO, estado desconhecido, dimensão em falta, essencial não aplicável")
    void dimensionUncertainty() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        assertThat(run(good.replace("\"condicoes\":{\"estado\":\"CONFORME\"", "\"condicoes\":{\"estado\":\"INDETERMINADO\"")).reason())
                .contains("incerteza").contains("CONDICOES");
        assertThat(run(good.replace("\"excepcoes\":{\"estado\":\"CONFORME\"", "\"excepcoes\":{\"estado\":\"TALVEZ\"")).reason())
                .contains("estado inválido").contains("excepcoes");
        assertThat(run(good.replace(",\"ambito_temporal\":{\"estado\":\"CONFORME\",\"nota\":\"n\"}", "")).reason())
                .contains("não avaliadas").contains("AMBITO_TEMPORAL");
        assertThat(run(good.replace("\"sujeito\":{\"estado\":\"CONFORME\"", "\"sujeito\":{\"estado\":\"NAO_APLICAVEL\"")).reason())
                .contains("essenciais");
        assertThat(run(good.replace("\"restricoes\":{\"estado\":\"CONFORME\"", "\"restricoes\":{\"estado\":\"NAO_APLICAVEL\""))
                .demonstrated()).as("dimensão não essencial pode não se aplicar").isTrue();
    }

    @Test
    @DisplayName("Passagem citada verificada deterministicamente: ausente, parafraseada, curta ou fora da unidade → não demonstrado")
    void citedPassageVerified() {
        assertThat(run(json("SUPORTA", "CONFORME", "")).reason()).contains("sem passagem");
        assertThat(run(json("SUPORTA", "CONFORME", "aplicam-se somente aos residentes em Portugal")).reason())
                .contains("inexistente na evidência");
        assertThat(run(json("SUPORTA", "CONFORME", "residentes")).demonstrated()).isFalse();
        assertThat(run(json("SUPORTA", "CONFORME", "São ainda deduzidos à coleta os pagamentos por conta do imposto")).reason())
                .as("existe na evidência, mas noutro número").contains("fora da unidade legal");
        assertThat(run(json("SUPORTA", "CONFORME", N5_ANCHOR).replace("\"justificacao\":\"O n.º 5 limita as deduções aos residentes.\"",
                "\"justificacao\":\"\"")).reason()).contains("sem justificação");
    }

    @Test
    @DisplayName("Erro: falha da chamada ao modelo fica registada (MODEL_FAILED) e é suporte não demonstrado")
    void callFailure() {
        when(ai.complete(any())).thenThrow(new IllegalStateException("timeout"));
        Assessment a = validator.assess(request());
        assertThat(a.call().outcome()).isEqualTo(StepOutcome.MODEL_FAILED);
        assertThat(gate.decide(a, STATEMENT, art78, unit).demonstrated()).isFalse();
        assertThat(gate.decide(null, STATEMENT, art78, unit).demonstrated()).isFalse();
    }

    @Test
    @DisplayName("O registo é re-verificável: as dimensões gravadas reproduzem a decisão; o registo recusa DEMONSTRATED sem SUPORTA")
    void recordRoundTrip() {
        Map<Dimension, DimensionStatus> dims = new EnumMap<>(allDimensions(DimensionStatus.CONFORME));
        var check = new com.knowledgeflow.research.entity.EntailmentCheck(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), null, Verdict.SUPORTA, dims, Map.of(Dimension.SUJEITO, "mesmo sujeito"), "j", N5_ANCHOR,
                true, com.knowledgeflow.research.enums.EntailmentDecision.DEMONSTRATED, "ok", java.time.OffsetDateTime.now());
        assertThat(check.dimensionStatuses()).isEqualTo(dims);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new com.knowledgeflow.research.entity.EntailmentCheck(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, Verdict.INDETERMINADO, dims,
                Map.of(), "j", N5_ANCHOR, true, com.knowledgeflow.research.enums.EntailmentDecision.DEMONSTRATED, "x",
                java.time.OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CORR-05: exactamente um objecto JSON — qualquer texto antes ou depois é rejeitado; só espaço em branco é tolerado")
    void textOutsideTheObjectRejected() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        assertThat(run("  \n" + good + "\n  ").demonstrated()).as("espaço em branco").isTrue();
        for (String wrapped : List.of("```json\n" + good + "\n```", "Resposta: " + good, good + " Fim.", good + "\n```",
                "Segue a avaliação.\n" + good, good + "\n{}", good + good, "[" + good + "]")) {
            EntailmentGate.Decision d = run(wrapped);
            assertThat(d.demonstrated()).as(wrapped).isFalse();
            assertThat(d.reason()).as(wrapped).contains("fora do protocolo");
        }
    }

    @Test
    @DisplayName("CORR-05: conteúdo inválido — chaves desconhecidas, tipos errados, vocabulário fora do protocolo, dimensão inexistente")
    void invalidContentRejected() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        assertThat(run(good.replace("{\"veredicto\"", "{\"confianca\":0.9,\"veredicto\"")).reason()).contains("chave desconhecida");
        assertThat(run(good.replace("\"veredicto\":\"SUPORTA\"", "\"veredicto\":\"suporta\"")).reason()).contains("veredicto inválido");
        assertThat(run(good.replace("\"veredicto\":\"SUPORTA\"", "\"veredicto\":true")).reason()).contains("veredicto inválido");
        assertThat(run(good.replace("\"veredicto\":\"SUPORTA\"", "\"veredicto\":[\"SUPORTA\"]")).reason()).contains("veredicto inválido");
        assertThat(run(good.replace("\"sujeito\":", "\"objecto\":")).reason()).contains("dimensão desconhecida");
        assertThat(run(good.replace("\"sujeito\":{\"estado\":\"CONFORME\",\"nota\":\"n\"}", "\"sujeito\":\"CONFORME\"")).reason())
                .contains("não é um objecto");
        assertThat(run(good.replace("\"passagem\":\"", "\"passagem\":1,\"x\":\"")).demonstrated()).isFalse();
        assertThat(run(good.replace("\"nota\":\"n\"}", "\"nota\":\"n\",\"peso\":1}")).reason()).contains("campo desconhecido");
        assertThat(run(good.replace(",\"predicado\":{\"estado\":\"CONFORME\",\"nota\":\"n\"}", "")).reason())
                .contains("dimensões não avaliadas").contains("PREDICADO");
        assertThat(run(good.replace("\"dimensoes\":{", "\"dimensoes\":{\"sujeito\":{\"estado\":\"NAO_CONFORME\"},")).reason())
                .as("dimensão duplicada com estados contraditórios").contains("fora do protocolo");
    }

    // -------------------------------------------------------------------------
    // KF-M02/CORR-09 — structured outputs, paragem do modelo e diagnóstico seguro
    // -------------------------------------------------------------------------

    private Assessment assess(String modelText, String stopReason) {
        when(ai.complete(any())).thenReturn(
                new AIResponse("anthropic", "claude-haiku-synthetic", modelText, 1800, 250, 900, stopReason));
        return validator.assess(request());
    }

    @Test
    @DisplayName("CORR-09: o pedido leva o schema da resposta (structured outputs)")
    void requestCarriesOutputSchema() {
        run(json("SUPORTA", "CONFORME", N5_ANCHOR));
        ArgumentCaptor<AIRequest> sent = ArgumentCaptor.forClass(AIRequest.class);
        verify(ai).complete(sent.capture());
        assertThat(sent.getValue().outputSchema()).isEqualTo(ModelEntailmentValidator.outputSchema());
    }

    @Test
    @DisplayName("CORR-09: schema estrito — tudo obrigatório, nada adicional, vocabulário igual ao do validador, sete dimensões")
    void outputSchemaIsStrictAndMatchesVocabulary() {
        com.fasterxml.jackson.databind.JsonNode schema = ModelEntailmentValidator.outputSchema();
        assertStrictObject(schema, "veredicto", "dimensoes", "passagem", "justificacao");
        assertThat(enumOf(schema.at("/properties/veredicto"))).containsExactlyInAnyOrder(
                java.util.Arrays.stream(Verdict.values()).map(Enum::name).toArray(String[]::new));
        com.fasterxml.jackson.databind.JsonNode dims = schema.at("/properties/dimensoes");
        assertStrictObject(dims, "sujeito", "predicado", "polaridade", "condicoes", "excepcoes", "restricoes", "ambito_temporal");
        dims.get("properties").forEach(d -> {
            assertStrictObject(d, "estado", "nota");
            assertThat(enumOf(d.at("/properties/estado"))).containsExactlyInAnyOrder(
                    java.util.Arrays.stream(DimensionStatus.values()).map(Enum::name).toArray(String[]::new));
        });
        // o schema devolvido é uma cópia: alterá-la não altera o que é enviado
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("additionalProperties", true);
        assertThat(ModelEntailmentValidator.outputSchema().get("additionalProperties").asBoolean()).isFalse();
    }

    private static void assertStrictObject(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        assertThat(node.get("type").asText()).isEqualTo("object");
        assertThat(node.get("additionalProperties").asBoolean(true)).isFalse();
        List<String> props = new java.util.ArrayList<>();
        node.get("properties").fieldNames().forEachRemaining(props::add);
        List<String> required = new java.util.ArrayList<>();
        node.get("required").forEach(r -> required.add(r.asText()));
        assertThat(props).containsExactlyInAnyOrder(fields);
        assertThat(required).containsExactlyInAnyOrder(fields);
    }

    private static List<String> enumOf(com.fasterxml.jackson.databind.JsonNode node) {
        assertThat(node.get("type").asText()).isEqualTo("string");
        List<String> values = new java.util.ArrayList<>();
        node.get("enum").forEach(v -> values.add(v.asText()));
        return values;
    }

    @Test
    @DisplayName("CORR-09: resposta estruturada válida com end_turn → leitura estrita aceita e a decisão demonstra")
    void validStructuredResponse() {
        Assessment a = assess(json("SUPORTA", "CONFORME", N5_ANCHOR), "end_turn");
        assertThat(a.call().outcome()).isEqualTo(StepOutcome.MODEL_COMPLETED);
        assertThat(gate.decide(a, STATEMENT, art78, unit).demonstrated()).isTrue();
    }

    @Test
    @DisplayName("CORR-09: max_tokens → truncada, fora do protocolo, mesmo que o texto pareça JSON completo (fail-closed)")
    void truncatedResponseFailsClosed() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        for (String text : List.of(good.substring(0, good.length() / 2), good)) {
            Assessment a = assess(text, "max_tokens");
            assertThat(a.call().outcome()).isEqualTo(StepOutcome.MODEL_UNPARSEABLE);
            EntailmentGate.Decision d = gate.decide(a, STATEMENT, art78, unit);
            assertThat(d.demonstrated()).isFalse();
            assertThat(d.reason()).contains("resposta truncada (max_tokens)");
            assertThat(a.call().detail()).contains("stop_reason=max_tokens");
        }
    }

    @Test
    @DisplayName("CORR-09: truncada sem motivo de paragem → JSON inválido; o diagnóstico mostra chavetas abertas")
    void truncatedWithoutStopReason() {
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR);
        Assessment a = assess(good.substring(0, good.indexOf("\"passagem\"")) + "\"passagem\":\"aplicam", null);
        assertThat(gate.decide(a, STATEMENT, art78, unit).demonstrated()).isFalse();
        assertThat(a.call().outcome()).isEqualTo(StepOutcome.MODEL_UNPARSEABLE);
        assertThat(a.call().detail()).contains("texto fora do objecto JSON").contains("início=OBJECTO")
                .contains("chavetas=ABERTAS(string por fechar)").doesNotContain("aplicam");
        Assessment b = assess(good.substring(0, good.length() - 1), null);
        assertThat(b.call().detail()).contains("texto fora do objecto JSON").contains("chavetas=ABERTAS(1)").contains("início=OBJECTO");
    }

    @Test
    @DisplayName("CORR-09: recusa do modelo → fora do protocolo, nunca demonstrado")
    void refusalFailsClosed() {
        Assessment a = assess(json("SUPORTA", "CONFORME", N5_ANCHOR), "refusal");
        EntailmentGate.Decision d = gate.decide(a, STATEMENT, art78, unit);
        assertThat(d.demonstrated()).isFalse();
        assertThat(d.reason()).contains("recusa do modelo");
    }

    @Test
    @DisplayName("CORR-09: respostas cercadas ou com texto continuam rejeitadas; diagnóstico só da forma, sem conteúdo")
    void fencedResponseRejectedWithSafeDiagnostic() {
        String secret = "SENTINELA-CONTEUDO-7Q";
        String good = json("SUPORTA", "CONFORME", N5_ANCHOR).replace("O n.º 5 limita as deduções aos residentes.", secret);
        Assessment fenced = assess("```json\n" + good + "\n```", "end_turn");
        assertThat(gate.decide(fenced, STATEMENT, art78, unit).demonstrated()).isFalse();
        assertThat(fenced.call().detail()).contains("texto fora do objecto JSON").contains("início=CERCA").contains("cercas=2")
                .contains("antes_do_objecto=7").contains("depois_do_objecto=3").contains("chavetas=EQUILIBRADAS")
                .contains("stop_reason=end_turn").contains("sha256=")
                .doesNotContain(secret).doesNotContain(N5_ANCHOR).doesNotContain("SUPORTA");
        Assessment prose = assess("Segue a avaliação: " + good, "end_turn");
        assertThat(prose.call().detail()).contains("início=TEXTO").contains("antes_do_objecto=18").doesNotContain(secret);
    }
}
