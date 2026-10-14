package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingFoundation;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.repository.KnowledgeQaGovernanceRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M4B (ADR-007): o risco e a validação humana persistidos das Q&amp;A efectivamente usadas chegam à
 * resposta documentada — risco agregado pelo fundamento mais restritivo (C4), piso SUGGESTED para
 * {@code requiresHumanValidation=true} (decisão 1-B), HIGH nunca conclusiva (C2), CRITICAL →
 * Resposta-limite + REQUIRED (decisão 2b), risco desconhecido fail-closed como HIGH.
 *
 * <p>Pipeline real pós-grounding: resolver de governança (repositório simulado), resolver de fontes
 * curadas, mapper, decisão, diagnóstico e projecção — sem base de dados nem provider.
 */
class M4BRiskPropagationTest {

    /** Metadados reais do piloto (2026-10-12). */
    private static final UUID G1 = UUID.fromString("f5faf908-956b-4843-914a-c2291d9a4a2e");
    private static final UUID G2 = UUID.fromString("82e6f5d9-aada-4baf-846c-4e5feebb64a0");
    private static final UUID CIVA_V2 = UUID.fromString("6e5d958f-0219-4941-a000-25885a22d2fd");
    private static final UUID ORG = UUID.fromString("7f58c283-f0a5-43c8-b83d-d8056e329a11");

    private record Row(UUID uuid, String getRiskLevel, Boolean getRequiresHumanValidation)
            implements KnowledgeQaGovernanceRow {
        @Override
        public String getId() {
            return uuid.toString();
        }
    }

    private KnowledgeQuestionAnswerRepository repository;
    private final List<Row> rows = new ArrayList<>();
    private FoundationGovernanceResolver resolver;
    // Fontes verificadas (CURRENT, ADR-008): estes testes isolam o risco M4B da actualidade.
    private final DocumentedTaxiaAnswerMapper mapper = FreshnessFixtures.mapper();
    private final AnswerProjectionService projection = new AnswerProjectionService();

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeQuestionAnswerRepository.class);
        when(repository.findGovernanceRowsByIdIn(any(), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(1);
            return rows.stream().filter(r -> ids.contains(r.uuid())).map(r -> (KnowledgeQaGovernanceRow) r).toList();
        });
        resolver = new FoundationGovernanceResolver(repository);
    }

    // ------------------------------------------------------------ casos reais

    @Test
    @DisplayName("G1 (LOW, rhv=true): risco LOW, validação humana true, Consulta documentada, parecer SUGGESTED")
    void g1() {
        rows.add(new Row(G1, "LOW", true));
        DocumentedTaxiaAnswer answer = answerFor(List.of(G1));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(resolver.resolve(ORG, foundations(List.of(G1))).requiresHumanValidation()).isTrue();
        assertThat(answer.internalDiagnostics().riskSignals())
                .anyMatch(s -> s.startsWith("aggregatedRiskLevel=LOW"))
                .anyMatch(s -> s.contains("knowledge-qa:" + G1) && s.contains("requiresHumanValidation=true"))
                .noneMatch(s -> s.contains("não é usado"));
    }

    @Test
    @DisplayName("G2 (MEDIUM, rhv=true): risco MEDIUM, Consulta documentada, parecer SUGGESTED")
    void g2() {
        rows.add(new Row(G2, "MEDIUM", true));
        DocumentedTaxiaAnswer answer = answerFor(List.of(G2));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.MEDIUM);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    @DisplayName("CIVA conservação v2 (LOW, rhv=false): risco LOW, parecer NONE (sem sinal independente)")
    void civaV2() {
        rows.add(new Row(CIVA_V2, "LOW", false));
        DocumentedTaxiaAnswer answer = answerFor(List.of(CIVA_V2));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(answer.warnings()).isEmpty();
    }

    @ParameterizedTest(name = "{0} ({1}, rhv={2}) → {3}")
    @CsvSource({
            "AT-FAQ-5930, MEDIUM, true, SUGGESTED",
            "AT-FAQ-2721, MEDIUM, true, SUGGESTED",
            "AT-FAQ-5795, MEDIUM, true, SUGGESTED"
    })
    void publishedAtFaqs_withTheirRealMetadata(String key, String risk, boolean rhv, ParecerRequirement parecer) {
        UUID id = UUID.nameUUIDFromBytes(key.getBytes());
        rows.add(new Row(id, risk, rhv));
        DocumentedTaxiaAnswer answer = answerFor(List.of(id));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.valueOf(risk));
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(parecer);
    }

    // ------------------------------------------------------------ HIGH / CRITICAL

    @Test
    @DisplayName("HIGH usado: nunca Consulta documentada conclusiva → com limitações, SUGGESTED")
    void high() {
        UUID id = qa("HIGH", false);
        DocumentedTaxiaAnswer answer = answerFor(List.of(id));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(answer.limitations()).anyMatch(l -> l.contains("risco elevado"));
    }

    @Test
    @DisplayName("CRITICAL usado: Resposta-limite + REQUIRED (decisão 2b), não tratado como HIGH")
    void critical() {
        UUID id = qa("CRITICAL", true);
        DocumentedTaxiaAnswer answer = answerFor(List.of(id));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.CRITICAL);
        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        assertThat(answer.limitations()).anyMatch(l -> l.contains("risco crítico"));
        assertThat(answer.nextSteps()).anyMatch(s -> s.contains("Pedido de parecer"));
    }

    // ------------------------------------------------------------ multi-candidato

    @ParameterizedTest(name = "{0} + {1} → {2} / {3} / {4}")
    @CsvSource({
            "LOW,      LOW,      LOW,      CONSULTA_DOCUMENTADA,               NONE",
            "LOW,      MEDIUM,   MEDIUM,   CONSULTA_DOCUMENTADA,               NONE",
            "MEDIUM,   MEDIUM,   MEDIUM,   CONSULTA_DOCUMENTADA,               NONE",
            "LOW,      HIGH,     HIGH,     CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "HIGH,     LOW,      HIGH,     CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "MEDIUM,   HIGH,     HIGH,     CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "LOW,      CRITICAL, CRITICAL, RESPOSTA_LIMITE,                    REQUIRED",
            "HIGH,     CRITICAL, CRITICAL, RESPOSTA_LIMITE,                    REQUIRED"
    })
    void mostRestrictiveFoundationWins_inAnyOrder(String a, String b, KnowledgeRiskLevel aggregated,
            AnswerType type, ParecerRequirement parecer) {
        DocumentedTaxiaAnswer answer = answerFor(List.of(qa(a, false), qa(b, false)));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(aggregated);
        assertThat(answer.answerType()).isEqualTo(type);
        assertThat(answer.parecerRequirement()).isEqualTo(parecer);
    }

    @ParameterizedTest(name = "rhv {0} + {1} → agregado {2}, parecer {3}")
    @CsvSource({
            "false, false, false, NONE",
            "false, true,  true,  SUGGESTED",
            "true,  false, true,  SUGGESTED",
            "true,  true,  true,  SUGGESTED"
    })
    void humanValidation_anyTrueWins(boolean a, boolean b, boolean aggregated, ParecerRequirement parecer) {
        List<UUID> ids = List.of(qa("LOW", a), qa("MEDIUM", b));
        FoundationGovernance governance = resolver.resolve(ORG, foundations(ids));
        DocumentedTaxiaAnswer answer = answerFor(ids);

        assertThat(governance.requiresHumanValidation()).isEqualTo(aggregated);
        assertThat(answer.parecerRequirement()).isEqualTo(parecer);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
    }

    @Test
    @DisplayName("Duas Q&A distintas com o mesmo título contam ambas: um HIGH não é escondido pela deduplicação")
    void sameTitle_doesNotHideHigh() {
        UUID low = qa("LOW", false);
        UUID high = qa("HIGH", false);
        GroundedAIResponse grounded = supported(List.of(new AnswerSource("Mesmo título", "Mesmo título", 0.95, low)),
                List.of(new GroundingFoundation(low, "Mesmo título"), new GroundingFoundation(high, "Mesmo título")));

        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse("Pergunta?", grounded, List.of(),
                resolver.resolve(ORG, grounded.foundations()));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
    }

    // ------------------------------------------------------------ risco desconhecido (fail-closed)

    @ParameterizedTest(name = "risco persistido \"{0}\" → RISK_UNKNOWN, decisão como HIGH")
    @CsvSource(value = {"NULL", "SEVERE", "low"}, nullValues = "NULL")
    void unknownRisk_isKeptUnknown_butDecidedAsHigh(String rawRisk) {
        UUID id = UUID.randomUUID();
        rows.add(new Row(id, rawRisk, false));
        FoundationGovernance governance = resolver.resolve(ORG, foundations(List.of(id)));
        DocumentedTaxiaAnswer answer = answerFor(List.of(id));

        assertThat(governance.riskUnknown()).isTrue();
        assertThat(governance.requiresHumanValidation()).isTrue();
        assertThat(answer.aggregatedRiskLevel()).isNull();
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(answer.limitations()).anyMatch(l -> l.contains("não está determinado"));
        assertThat(answer.internalDiagnostics().riskSignals())
                .anyMatch(s -> s.contains("RISK_UNKNOWN"))
                .anyMatch(s -> s.contains("tratado como HIGH"));
    }

    @Test
    @DisplayName("Fail-closed: Q&A inexistente, fundamento DOCUMENT sem Q&A e falha de leitura → risco desconhecido")
    void missingRow_documentFoundation_andReadFailure_areUnknown() {
        FoundationGovernance missing = resolver.resolve(ORG, foundations(List.of(UUID.randomUUID())));
        assertThat(missing.riskUnknown()).isTrue();
        assertThat(missing.decisionRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);

        FoundationGovernance document = resolver.resolve(ORG, List.of(new GroundingFoundation(null, "Documento")));
        assertThat(document.riskUnknown()).isTrue();

        when(repository.findGovernanceRowsByIdIn(any(), anyCollection())).thenThrow(new IllegalStateException("db down"));
        FoundationGovernance failure = resolver.resolve(ORG, foundations(List.of(UUID.randomUUID())));
        assertThat(failure.riskUnknown()).isTrue();
        assertThat(failure.requiresHumanValidation()).isTrue();
        assertThat(failure.foundations()).singleElement()
                .satisfies(f -> assertThat(f.unknownReason()).contains("falha de leitura"));
    }

    @Test
    @DisplayName("Risco desconhecido + CRITICAL conhecido: CRITICAL (nada o excede)")
    void unknownPlusCritical_isCritical() {
        UUID unknown = UUID.randomUUID();
        rows.add(new Row(unknown, null, false));
        DocumentedTaxiaAnswer answer = answerFor(List.of(unknown, qa("CRITICAL", false)));

        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.CRITICAL);
        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
    }

    // ------------------------------------------------------------ CRITICAL: corpo conclusivo suprimido (ADR-007)

    private static final String CONCLUSIVE =
            "Conclusão: a sociedade pode deduzir integralmente o prejuízo de 250 000 € no exercício de 2026 "
            + "e não está sujeita a qualquer correcção da AT.";

    private DocumentedTaxiaAnswer conclusiveAnswerFor(List<UUID> ids) {
        List<AnswerSource> sources = ids.stream().map(id -> new AnswerSource("Caso " + id, "Caso " + id, 0.95, id)).toList();
        GroundedAIResponse grounded = new GroundedAIResponse(CONCLUSIVE, AnswerSupportStatus.SUPPORTED, "Contexto suficiente.",
                sources, List.of(), List.of(), false, null, true, false, null, 0, "stub", "stub", 0, 0, 0L, foundations(ids));
        return mapper.fromGroundedResponse("Pergunta?", grounded,
                sources.stream().map(s -> ResolvedAnswerSource.curated(s, FreshnessFixtures.verifiedLegislation(
                        UUID.randomUUID(), "Código do IRC — Artigo 52.º", "CIRC, art. 52.º", null))).toList(),
                resolver.resolve(ORG, grounded.foundations()));
    }

    @Test
    @DisplayName("CRITICAL A–D: corpo conclusivo do LLM não chega à resposta; RESPOSTA_LIMITE, REQUIRED, Pedido de parecer")
    void critical_suppressesTheGeneratedConclusion() {
        DocumentedTaxiaAnswer answer = conclusiveAnswerFor(List.of(qa("CRITICAL", true)));

        assertThat(answer.technicalAnswer()).isEqualTo(DocumentedTaxiaAnswerMapper.CRITICAL_LIMIT_ANSWER);
        assertThat(answer.shortAnswer()).isEqualTo(DocumentedTaxiaAnswerMapper.CRITICAL_LIMIT_ANSWER);
        assertThat(answer.toString()).doesNotContain("250 000").doesNotContain("deduzir integralmente");
        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        assertThat(answer.nextSteps()).anyMatch(s -> s.contains("Pedido de parecer"));
        assertThat(answer.sources()).isNotEmpty() // fontes de enquadramento preservadas (política §6)
                .noneMatch(SourceEvidence::supportsConclusion);
        assertThat(answer.limitations()).noneMatch(l -> l.contains("A resposta baseia-se"));
        assertThat(answer.internalDiagnostics().riskSignals()).anyMatch(s -> s.contains("suprimida"));
    }

    @ParameterizedTest(name = "CRITICAL E/F: projecção {0} não expõe o corpo conclusivo")
    @CsvSource({"INTERNAL", "DEMO", "EXTERNAL"})
    void critical_noProjectionExposesTheConclusion(VisibilityLevel level) {
        AnswerProjection projected = projection.project(conclusiveAnswerFor(List.of(qa("LOW", false), qa("CRITICAL", false))), level);

        assertThat(projected.visibleAnswer()).isEqualTo(DocumentedTaxiaAnswerMapper.CRITICAL_LIMIT_ANSWER);
        assertThat(projected.toString()).doesNotContain("250 000").doesNotContain("deduzir integralmente");
        assertThat(projected.visibleAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(projected.visibleParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
    }

    @ParameterizedTest(name = "CRITICAL G/H: {0} mantém o corpo gerado (sem herdar a supressão)")
    @CsvSource({"HIGH, CONSULTA_DOCUMENTADA_COM_LIMITACOES", "MEDIUM, CONSULTA_DOCUMENTADA", "LOW, CONSULTA_DOCUMENTADA"})
    void nonCritical_keepsTheGeneratedBody(String risk, AnswerType type) {
        DocumentedTaxiaAnswer answer = conclusiveAnswerFor(List.of(qa(risk, false)));

        assertThat(answer.technicalAnswer()).isEqualTo(CONCLUSIVE);
        assertThat(answer.answerType()).isEqualTo(type);
        assertThat(answer.sources()).allMatch(SourceEvidence::supportsConclusion);
        assertThat(projection.project(answer, VisibilityLevel.EXTERNAL).visibleAnswer()).isEqualTo(CONCLUSIVE);
    }

    // ------------------------------------------------------------ revisão independente

    @Test
    @DisplayName("Revisão #2: confiança e diagnóstico explicam o risco (não a fraqueza das fontes)")
    void riskDowngrade_explainsTheRiskReason() {
        DocumentedTaxiaAnswer high = answerFor(List.of(qa("HIGH", false)));
        assertThat(high.confidenceSummary()).contains("risco elevado");
        assertThat(high.internalDiagnostics().decisionSignals())
                .anyMatch(s -> s.contains("restringido pelo risco"))
                .noneMatch(s -> s.contains("fraqueza documental"));

        DocumentedTaxiaAnswer critical = answerFor(List.of(qa("CRITICAL", false)));
        assertThat(critical.confidenceSummary()).contains("risco crítico");

        UUID unknown = UUID.randomUUID();
        rows.add(new Row(unknown, null, false));
        assertThat(answerFor(List.of(unknown)).confidenceSummary()).contains("não determinado");
    }

    @Test
    @DisplayName("Re-revisão #1: risco não restritivo da decisão mantém o texto de suporte e acrescenta o risco")
    void riskText_isAppended_whenSupportAlreadyLimited() {
        UUID id = qa("HIGH", false);
        GroundedAIResponse partial = new GroundedAIResponse("Resposta.", AnswerSupportStatus.PARTIALLY_SUPPORTED,
                "Parcial.", List.of(new AnswerSource("Caso", "Caso", 0.95, id)), List.of(), List.of(), true, null,
                true, false, null, 1, "stub", "stub", 0, 0, 0L, List.of(new GroundingFoundation(id, "Caso")));

        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse("P?", partial, List.of(),
                resolver.resolve(ORG, partial.foundations()));

        assertThat(answer.confidenceSummary()).startsWith("Suporte documental parcial").contains("risco elevado");
    }

    @Test
    @DisplayName("Re-revisão #2: HIGH conhecido + desconhecido — o HIGH continua visível nas limitações e na confiança")
    void knownHigh_staysVisible_withUnknown() {
        UUID unknown = UUID.randomUUID();
        rows.add(new Row(unknown, null, false));
        DocumentedTaxiaAnswer answer = answerFor(List.of(unknown, qa("HIGH", false)));

        assertThat(answer.limitations()).anyMatch(l -> l.contains("risco elevado")).anyMatch(l -> l.contains("não está determinado"));
        assertThat(answer.confidenceSummary()).contains("risco elevado");
        assertThat(answer.aggregatedRiskLevel()).isNull();
    }

    @Test
    @DisplayName("Re-revisão #3: organização desconhecida → motivo explícito")
    void unknownOrganization_reason() {
        FoundationGovernance governance = resolver.resolve(null, foundations(List.of(qa("LOW", false))));
        assertThat(governance.foundations()).singleElement()
                .satisfies(f -> assertThat(f.unknownReason()).isEqualTo("organização desconhecida"));
    }

    @Test
    @DisplayName("Revisão #4: desconhecido + CRITICAL — o diagnóstico diz que prevalece o CRITICAL")
    void unknownPlusCritical_diagnosticIsConsistent() {
        UUID unknown = UUID.randomUUID();
        rows.add(new Row(unknown, null, false));
        DocumentedTaxiaAnswer answer = answerFor(List.of(unknown, qa("CRITICAL", false)));

        assertThat(answer.internalDiagnostics().riskSignals())
                .anyMatch(s -> s.contains("prevalece o CRITICAL"))
                .noneMatch(s -> s.contains("tratado como HIGH"));
    }

    @Test
    @DisplayName("Revisão #5: o pipeline de produção exige governança (sem default silencioso)")
    void productionOverloads_requireGovernance() {
        GroundedAIResponse grounded = supported(List.of(), List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> mapper.fromGroundedResponse("P?", grounded, List.of(), null))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnswerDecisionService().decide(
                AnswerSupportStatus.SUPPORTED, false, List.of(), List.of(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------ sem fundamentos / legado

    @Test
    @DisplayName("Resposta-limite antes do LLM (sem fundamentos): risco ausente, comportamento próprio inalterado")
    void noFoundations_keepsLimitAnswerBehaviour() {
        GroundedAIResponse refusal = new GroundedAIResponse("Sem documentação.", AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                "Sem candidatos.", List.of(), List.of(), List.of(), false, null, false, false, null, 0,
                "none", "none", 0, 0, 0L, List.of());

        DocumentedTaxiaAnswer withGovernance = mapper.fromGroundedResponse("Pergunta?", refusal, List.of(),
                resolver.resolve(ORG, refusal.foundations()));
        DocumentedTaxiaAnswer legacy = mapper.fromGroundedResponse("Pergunta?", refusal, List.of());

        assertThat(withGovernance.aggregatedRiskLevel()).isNull();
        assertThat(withGovernance.answerType()).isEqualTo(legacy.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(withGovernance.parecerRequirement()).isEqualTo(legacy.parecerRequirement());
        assertThat(withGovernance.internalDiagnostics().riskSignals())
                .contains("aggregatedRiskLevel ausente (sem fundamentos usados)");
    }

    @Test
    @DisplayName("supportStatus continua independente: PARTIALLY_SUPPORTED + LOW/rhv=false mantém a decisão anterior")
    void supportStatusStaysIndependent() {
        UUID id = qa("LOW", false);
        GroundedAIResponse partial = new GroundedAIResponse("Resposta.", AnswerSupportStatus.PARTIALLY_SUPPORTED,
                "Parcial.", List.of(new AnswerSource("Caso", "Caso", 0.95, id)), List.of(), List.of(), true, null,
                true, false, null, 1, "stub", "stub", 0, 0, 0L, List.of(new GroundingFoundation(id, "Caso")));

        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse("Pergunta?", partial, List.of(),
                resolver.resolve(ORG, partial.foundations()));
        DocumentedTaxiaAnswer legacy = mapper.fromGroundedResponse("Pergunta?", partial, List.of());

        assertThat(answer.supportStatus()).isEqualTo(AnswerSupportStatus.PARTIALLY_SUPPORTED);
        assertThat(answer.answerType()).isEqualTo(legacy.answerType());
        assertThat(answer.parecerRequirement()).isEqualTo(legacy.parecerRequirement());
        assertThat(answer.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
    }

    // ------------------------------------------------------------ projecção

    @ParameterizedTest(name = "projecção {0}: answerType e parecer preservados (G2 SUGGESTED, CRITICAL REQUIRED)")
    @CsvSource({"INTERNAL", "DEMO", "EXTERNAL"})
    void projection_preservesRiskDrivenDecision(VisibilityLevel level) {
        rows.add(new Row(G2, "MEDIUM", true));
        AnswerProjection g2 = projection.project(answerFor(List.of(G2)), level);
        assertThat(g2.visibleAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(g2.visibleParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);

        AnswerProjection critical = projection.project(answerFor(List.of(qa("CRITICAL", false))), level);
        assertThat(critical.visibleAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(critical.visibleParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        assertThat(critical.visibleLimitations()).anyMatch(l -> l.contains("risco crítico"));
        if (level != VisibilityLevel.INTERNAL) {
            assertThat(critical.hiddenDiagnostics()).noneMatch(s -> s.contains("knowledge-qa:"));
        }
    }

    // ------------------------------------------------------------ helpers

    private UUID qa(String risk, boolean rhv) {
        UUID id = UUID.randomUUID();
        rows.add(new Row(id, risk, rhv));
        return id;
    }

    private static List<GroundingFoundation> foundations(List<UUID> ids) {
        return ids.stream().map(id -> new GroundingFoundation(id, "Caso " + id)).toList();
    }

    /** SUPPORTED com uma fonte por Q&amp;A e as fontes curadas legislativas reais (como G1/G2). */
    private DocumentedTaxiaAnswer answerFor(List<UUID> ids) {
        List<AnswerSource> sources = ids.stream()
                .map(id -> new AnswerSource("Caso " + id, "Caso " + id, 0.95, id)).toList();
        GroundedAIResponse grounded = supported(sources, foundations(ids));
        List<ResolvedAnswerSource> curated = sources.stream().map(s -> ResolvedAnswerSource.curated(s,
                FreshnessFixtures.verifiedLegislation(UUID.randomUUID(),
                        "Código do IVA — Artigo 36.º — Prazo de emissão e formalidades das faturas",
                        "CIVA, art. 36.º, n.os 1 e 2",
                        "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/civa_rep/Pages/iva36.aspx")))
                .toList();
        return mapper.fromGroundedResponse("Pergunta?", grounded, curated, resolver.resolve(ORG, grounded.foundations()));
    }

    private static GroundedAIResponse supported(List<AnswerSource> sources, List<GroundingFoundation> foundations) {
        return new GroundedAIResponse("Resposta fundamentada.", AnswerSupportStatus.SUPPORTED, "Contexto suficiente.",
                sources, List.of(), List.of(), false, null, true, false, null, 0, "stub", "stub", 0, 0, 0L,
                foundations);
    }
}
