package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conversor aditivo de {@link GroundedAIResponse} para {@link DocumentedTaxiaAnswer}
 * (tarefa D4, evoluído em D5/D6).
 *
 * <p>Não altera o comportamento do fluxo actual ({@code GroundingService}/{@code /ask});
 * apenas projecta a resposta já produzida no contrato documentado. Responsabilidades
 * separadas:
 * <ul>
 *   <li>{@link SourceAssessmentService} (D5) avalia cada fonte;</li>
 *   <li>{@link AnswerDecisionService} (D6) decide forma/prudência ({@link AnswerType},
 *       {@link ParecerRequirement}, resumos e listas);</li>
 *   <li>este mapper monta o DTO {@link DocumentedTaxiaAnswer}.</li>
 * </ul>
 *
 * <p>A projecção por {@link VisibilityLevel} continua reservada para D7. O
 * {@code aggregatedRiskLevel} vem dos fundamentos usados ({@link FoundationGovernance}, M4B/ADR-007);
 * as variantes sem governança (caminhos legados/teste) mantêm-no {@code null}.
 */
@Component
public class DocumentedTaxiaAnswerMapper {

    /**
     * Corpo seguro da Resposta-limite quando um fundamento CRITICAL é usado (ADR-007, decisão 2b): a
     * conclusão gerada pelo LLM nunca é exposta; a resposta encaminha para Pedido de parecer.
     */
    public static final String CRITICAL_LIMIT_ANSWER =
            "Esta questão assenta num fundamento de risco crítico. A TaxIA não apresenta conclusão fiscal "
            + "automática neste caso: a matéria exige apreciação profissional do caso concreto. Submeta um "
            + "Pedido de parecer; as fontes indicadas servem apenas de enquadramento.";

    private final SourceAssessmentService sourceAssessmentService;
    private final AnswerDecisionService answerDecisionService;
    private final InternalDiagnosticsBuilder internalDiagnosticsBuilder;

    private final SourceFreshnessPolicy sourceFreshnessPolicy;

    /** Testes/legado: política de actualidade com o relógio do sistema. */
    public DocumentedTaxiaAnswerMapper(SourceAssessmentService sourceAssessmentService,
            AnswerDecisionService answerDecisionService,
            InternalDiagnosticsBuilder internalDiagnosticsBuilder) {
        this(sourceAssessmentService, answerDecisionService, internalDiagnosticsBuilder, new SourceFreshnessPolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentedTaxiaAnswerMapper(SourceAssessmentService sourceAssessmentService,
            AnswerDecisionService answerDecisionService,
            InternalDiagnosticsBuilder internalDiagnosticsBuilder,
            SourceFreshnessPolicy sourceFreshnessPolicy) {
        this.sourceAssessmentService = sourceAssessmentService;
        this.answerDecisionService = answerDecisionService;
        this.internalDiagnosticsBuilder = internalDiagnosticsBuilder;
        this.sourceFreshnessPolicy = sourceFreshnessPolicy;
    }

    /**
     * Converte uma resposta de grounding no contrato documentado.
     *
     * @param question pergunta original recebida
     * @param grounded resposta de grounding (ponto de compatibilidade)
     * @return resposta documentada equivalente
     */
    public DocumentedTaxiaAnswer fromGroundedResponse(String question, GroundedAIResponse grounded) {
        return build(question, grounded, mapSources(grounded.sources(), grounded.supportStatus()),
                FoundationGovernance.notAssessed());
    }

    /**
     * Variante com as fontes já resolvidas pelo {@link CuratedSourceResolver} (M3): cada fonte
     * curada passa a {@link SourceEvidence} com os seus dados reais (título, tipo, referência
     * legal, URL); as entradas sem curadoria mantêm o mapeamento anterior. O mapper continua puro.
     */
    public DocumentedTaxiaAnswer fromGroundedResponse(
            String question, GroundedAIResponse grounded, List<ResolvedAnswerSource> resolvedSources) {
        return build(question, grounded, mapResolvedSources(resolvedSources, grounded.supportStatus()),
                FoundationGovernance.notAssessed());
    }

    /**
     * Pipeline de produção (M4B, ADR-007): fontes curadas resolvidas e risco/validação humana
     * agregados dos fundamentos usados.
     */
    public DocumentedTaxiaAnswer fromGroundedResponse(
            String question, GroundedAIResponse grounded, List<ResolvedAnswerSource> resolvedSources,
            FoundationGovernance governance) {
        java.util.Objects.requireNonNull(governance, "governance is required (M4B)");
        // CRITICAL (ADR-007): as fontes ficam como enquadramento — não sustentam uma conclusão exposta.
        AnswerSupportStatus sourcesStatus = suppressesGeneratedAnswer(governance) ? null : grounded.supportStatus();
        return build(question, grounded, mapResolvedSources(resolvedSources, sourcesStatus), governance);
    }

    private DocumentedTaxiaAnswer build(String question, GroundedAIResponse grounded, List<SourceEvidence> sources,
            FoundationGovernance governance) {
        AnswerSupportStatus supportStatus = grounded.supportStatus();
        boolean requiresHumanValidation = grounded.requiresHumanValidation() || governance.requiresHumanValidation();
        // CRITICAL usado (ADR-007): a geração pode existir como etapa técnica, mas a conclusão gerada
        // não chega a nenhum campo da resposta — corpo substituído pelo texto seguro de Resposta-limite.
        // As fontes mantêm-se (a Resposta-limite organiza enquadramento e fontes — política §6).
        String answerBody = suppressesGeneratedAnswer(governance) ? CRITICAL_LIMIT_ANSWER : grounded.answer();

        AnswerDecision decision = answerDecisionService.decide(
                supportStatus,
                grounded.requiresHumanValidation(),
                sources,
                grounded.limitations(),
                grounded.validationMessage(),
                governance);

        InternalDiagnostics internalDiagnostics = internalDiagnosticsBuilder.build(
                supportStatus,
                requiresHumanValidation,
                sources,
                decision,
                governance.aggregatedRiskLevel(),
                governance);

        return new DocumentedTaxiaAnswer(
                UUID.randomUUID().toString(),
                question,
                null,
                answerBody,
                answerBody,
                decision.answerType(),
                supportStatus,
                governance.aggregatedRiskLevel(),
                decision.parecerRequirement(),
                VisibilityLevel.INTERNAL,
                decision.overallFreshnessStatus(),
                decision.confidenceSummary(),
                decision.limitations(),
                List.of(),
                safeList(grounded.missingInformation()),
                decision.sourceSummary(),
                sources,
                decision.warnings(),
                decision.nextSteps(),
                internalDiagnostics);
    }

    /** Se a conclusão gerada pelo LLM deve ser suprimida (fundamento CRITICAL usado, ADR-007). */
    public static boolean suppressesGeneratedAnswer(FoundationGovernance governance) {
        return governance != null && governance.decisionRiskLevel() == com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel.CRITICAL;
    }

    private List<SourceEvidence> mapSources(List<AnswerSource> sources, AnswerSupportStatus supportStatus) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        boolean supportsConclusion = supportStatus == AnswerSupportStatus.SUPPORTED;
        List<SourceEvidence> mapped = new ArrayList<>(sources.size());
        for (AnswerSource source : sources) {
            mapped.add(evidence(source, null, source.title(), null, source.reference(), null,
                    supportsConclusion, List.of()));
        }
        return List.copyOf(mapped);
    }

    private List<SourceEvidence> mapResolvedSources(
            List<ResolvedAnswerSource> resolvedSources, AnswerSupportStatus supportStatus) {
        if (resolvedSources == null || resolvedSources.isEmpty()) {
            return List.of();
        }
        boolean supportsConclusion = supportStatus == AnswerSupportStatus.SUPPORTED;
        List<SourceEvidence> mapped = new ArrayList<>(resolvedSources.size());
        for (ResolvedAnswerSource resolved : resolvedSources) {
            AnswerSource origin = resolved.origin();
            if (!resolved.isCurated()) {
                mapped.add(evidence(origin, null, origin.title(), null, origin.reference(), null,
                        supportsConclusion, List.of()));
                continue;
            }
            ResolvedAnswerSource.CuratedSource curated = resolved.curated();
            // A avaliação (D5) corre sobre os dados reais da fonte curada, com a política actual.
            AnswerSource assessed = new AnswerSource(
                    curated.title(), curated.legalReference(), origin.relevanceScore());
            // Q&A de origem: rastreabilidade interna (relatedSources é ocultado em DEMO/EXTERNAL).
            List<String> relatedSources = origin.sourceQaId() != null
                    ? List.of("knowledge-qa:" + origin.sourceQaId())
                    : List.of();
            // C8 / ADR-008: actualidade calculada a partir dos dados governados da fonte curada.
            SourceFreshnessPolicy.Assessment freshness = sourceFreshnessPolicy.assess(facts(curated));
            mapped.add(evidence(assessed,
                    curated.id() != null ? curated.id().toString() : null,
                    curated.title(), curated.sourceType(), curated.legalReference(), curated.url(),
                    supportsConclusion, relatedSources, freshness));
        }
        return List.copyOf(mapped);
    }

    /** Dados governados da fonte curada; sem eles, só tipo/título/referência (UNCERTAIN ou OUTDATED). */
    private static SourceFreshnessPolicy.Facts facts(ResolvedAnswerSource.CuratedSource curated) {
        if (curated.freshnessFacts() != null) {
            return curated.freshnessFacts();
        }
        com.knowledgeflow.knowledge.enums.KnowledgeSourceType type = null;
        try {
            type = curated.sourceType() != null
                    ? com.knowledgeflow.knowledge.enums.KnowledgeSourceType.valueOf(curated.sourceType()) : null;
        } catch (IllegalArgumentException e) {
            type = null;
        }
        return new SourceFreshnessPolicy.Facts(type, curated.title(), curated.legalReference(), null, null, false,
                false, false, null, false);
    }

    private SourceEvidence evidence(AnswerSource assessed, String sourceId, String title, String sourceType,
            String legalReference, String url, boolean supportsConclusion, List<String> relatedSources) {
        return evidence(assessed, sourceId, title, sourceType, legalReference, url, supportsConclusion,
                relatedSources, null);
    }

    /**
     * {@code freshness} {@code null}: fonte sem curadoria (só marcadores textuais → UNCERTAIN/OUTDATED);
     * senão, a actualidade governada (ADR-008), com o motivo nas notas internas.
     */
    private SourceEvidence evidence(AnswerSource assessed, String sourceId, String title, String sourceType,
            String legalReference, String url, boolean supportsConclusion, List<String> relatedSources,
            SourceFreshnessPolicy.Assessment freshness) {
        SourceRole sourceRole = sourceAssessmentService.assessSourceRole(assessed);
        return new SourceEvidence(
                sourceId,
                title,
                sourceType,
                sourceAssessmentService.assessAuthorityLevel(assessed),
                sourceRole,
                sourceAssessmentService.assessSourceQuality(assessed),
                sourceAssessmentService.buildSourceCore(assessed),
                sourceAssessmentService.buildSourceDiversityGroup(assessed),
                sourceAssessmentService.assessSourceDiversity(assessed),
                freshness != null ? freshness.status() : sourceAssessmentService.assessFreshnessStatus(assessed),
                legalReference,
                url,
                true,
                supportsConclusion,
                false,
                false,
                sourceRole == SourceRole.DERIVATIVE_REPLICATED,
                relatedSources,
                null,
                freshness != null ? List.of("freshness: " + freshness.status() + " — " + freshness.reason()) : List.of());
    }

    private List<String> safeList(List<String> list) {
        return list != null ? List.copyOf(list) : List.of();
    }
}
