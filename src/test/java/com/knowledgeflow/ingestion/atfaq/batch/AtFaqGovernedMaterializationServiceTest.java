package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for governed AT-FAQ draft materialization (Bloco E — E8A).
 *
 * <p>Proves that materialization turns only {@code READY_FOR_FUTURE_PUBLICATION} plan candidates
 * into curable in-memory drafts, deterministically, and never persists/publishes/indexes. Pure
 * unit test: no Spring context, no database, no HTTP, no LLM, no publication service, no embedding
 * indexer.
 */
class AtFaqGovernedMaterializationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");

    private AtFaqGovernedMaterializationService materializationService;
    private AtFaqKnowledgeQaDraftAssembler assembler;
    private AtFaqGovernedPublicationPlan plan;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, clock);
        AtFaqPreCurationService preCurationService =
                new AtFaqPreCurationService(batchService, normalizer, clock);
        AtFaqReviewService reviewService = new AtFaqReviewService(clock);
        AtFaqGovernedPublicationPlanService planService =
                new AtFaqGovernedPublicationPlanService(clock);
        assembler = new AtFaqKnowledgeQaDraftAssembler();
        materializationService = new AtFaqGovernedMaterializationService(clock, assembler);

        AtFaqPreCurationResult preCuration =
                preCurationService.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqReviewResult review = reviewService.review(preCuration, List.of(
                AtFaqReviewDecision.of("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE, "test-reviewer", "Base sólida."),
                AtFaqReviewDecision.of("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW, "test-reviewer", "Falta base legal."),
                AtFaqReviewDecision.of("AT-FAQ-1003", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Duplicado."),
                AtFaqReviewDecision.of("AT-FAQ-1004", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Conflito."),
                AtFaqReviewDecision.of("AT-FAQ-1005", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Risco alto."),
                AtFaqReviewDecision.of("AT-FAQ-1006", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Sem resposta técnica.")),
                "test-reviewer");
        plan = planService.plan(preCuration, review, "test-planner");
    }

    // --- service, full-pipeline ---------------------------------------------

    @Test
    void materializesOnlyReadyCandidates() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(result.batchId()).isEqualTo(plan.batchId());
        assertThat(result.materializedBy()).isEqualTo("test-materializer");
        assertThat(result.materializedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(result.totals().totalCandidates()).isEqualTo(6);
        assertThat(result.totals().readyFromPlan()).isEqualTo(1);
        assertThat(result.totals().materialized()).isEqualTo(1);
        assertThat(result.itemResults()).hasSize(1);
        assertThat(result.itemResults().get(0).externalId()).isEqualTo("AT-FAQ-1001");
        assertThat(result.itemResults().get(0).materialized()).isTrue();
    }

    @Test
    void ignoresAssistedManualBlockedDeferred() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(result.totals().skipped()).isEqualTo(5);
        assertThat(result.itemResults())
                .noneMatch(i -> List.of("AT-FAQ-1002", "AT-FAQ-1003", "AT-FAQ-1004",
                        "AT-FAQ-1005", "AT-FAQ-1006").contains(i.externalId()));
    }

    @Test
    void materializedIsGreaterThanZeroForCleanItem() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");
        assertThat(result.totals().materialized()).isPositive();
    }

    @Test
    void publishedAndIndexedAreAlwaysZero() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(result.totals().published()).isZero();
        assertThat(result.totals().indexed()).isZero();
        assertThat(result.itemResults()).allSatisfy(i -> {
            assertThat(i.published()).isFalse();
            assertThat(i.indexed()).isFalse();
        });
    }

    @Test
    void nothingIsPersistedPublishedOrIndexed() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(result.totals().persisted()).isZero();
        assertThat(result.itemResults()).allSatisfy(i -> {
            assertThat(i.persisted()).isFalse();
            assertThat(i.knowledgeQaId()).isNull();
        });
    }

    @Test
    void materializedDraftCarriesCuratedFields() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");
        AtFaqMaterializationCandidate draft = result.itemResults().get(0).draft();

        assertThat(draft).isNotNull();
        assertThat(draft.normalizedQuestion()).isNotBlank();
        assertThat(draft.shortAnswer()).isNotBlank();
        assertThat(draft.technicalAnswer()).isNotBlank();
        assertThat(draft.topic()).isNotNull();
        assertThat(draft.riskLevel()).isNotNull();
        assertThat(draft.jurisdiction()).isEqualTo("PT");
        assertThat(draft.sources()).isNotEmpty();
        assertThat(draft.sources()).anyMatch(AtFaqMaterializationSourceCandidate::official);
        assertThat(draft.legalReferences()).isNotEmpty();
    }

    @Test
    void draftCurationStatusIsConservativeNonPublished() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");
        AtFaqMaterializationCandidate draft = result.itemResults().get(0).draft();

        assertThat(draft.intendedCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        // Never eligible for RAG: only VALIDATED feeds the index.
        assertThat(draft.intendedCurationStatus()).isNotEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test
    void totalsAreConsistent() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");
        var t = result.totals();

        assertThat(t.readyFromPlan()).isEqualTo(t.materialized() + t.blocked());
        assertThat(t.skipped() + t.readyFromPlan()).isEqualTo(t.totalCandidates());
    }

    @Test
    void executionIsDeterministic() {
        AtFaqMaterializationResult first = materializationService.materializeDrafts(plan, "test-materializer");
        AtFaqMaterializationResult second = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(second).isEqualTo(first);
        assertThat(second.itemResults()).isEqualTo(first.itemResults());
        assertThat(second.materializedAt()).isEqualTo(first.materializedAt());
    }

    @Test
    void reprocessingIsIdempotentWithoutDuplicates() {
        AtFaqMaterializationResult first = materializationService.materializeDrafts(plan, "test-materializer");
        AtFaqMaterializationResult second = materializationService.materializeDrafts(plan, "test-materializer");

        assertThat(first.itemResults()).hasSameSizeAs(second.itemResults());
        assertThat(first.itemResults()).extracting(AtFaqMaterializationItemResult::externalId)
                .doesNotHaveDuplicates();
    }

    @Test
    void globalNextActionsReinforceNothingPublished() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");
        assertThat(result.nextActions()).anyMatch(a -> a.contains("published=0") && a.contains("indexed=0"));
    }

    @Test
    void outputContainsNoRawHtmlPromptsOrChunks() {
        AtFaqMaterializationResult result = materializationService.materializeDrafts(plan, "test-materializer");

        List<String> texts = new ArrayList<>();
        texts.addAll(result.globalWarnings());
        texts.addAll(result.blockingErrors());
        texts.addAll(result.nextActions());
        for (AtFaqMaterializationItemResult item : result.itemResults()) {
            texts.add(item.normalizedQuestion());
            texts.addAll(item.warnings());
            texts.addAll(item.blockingReasons());
            texts.addAll(item.nextActions());
            AtFaqMaterializationCandidate d = item.draft();
            if (d != null) {
                texts.add(d.normalizedQuestion());
                texts.add(d.shortAnswer());
                texts.add(d.technicalAnswer());
                texts.addAll(d.legalReferences());
                texts.addAll(d.warnings());
                for (AtFaqMaterializationSourceCandidate s : d.sources()) {
                    texts.add(s.title());
                    texts.add(s.url());
                    texts.add(s.legalReference());
                    texts.addAll(s.warnings());
                }
            }
        }
        for (String t : texts) {
            if (t != null) {
                assertThat(t).doesNotContain("<").doesNotContain("```");
                assertThat(t.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }

    // --- assembler guards (built candidates) --------------------------------

    @Test
    void candidateWithoutTechnicalAnswerIsBlocked() {
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate(null, List.of(officialSource()), List.of("Artigo 41.º do CIVA"), passedGuard()));

        assertThat(result.materialized()).isFalse();
        assertThat(result.draft()).isNull();
        assertThat(result.blockingReasons()).contains("technical-answer-present");
    }

    @Test
    void candidateWithoutSourceIsBlocked() {
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate("Resposta técnica.", List.of(), List.of("Artigo 41.º do CIVA"), passedGuard()));

        assertThat(result.materialized()).isFalse();
        assertThat(result.blockingReasons()).contains("at-least-one-source");
    }

    @Test
    void candidateWithoutOfficialSourceIsBlocked() {
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate("Resposta técnica.", List.of(nonOfficialSource()), List.of("Artigo 41.º do CIVA"), passedGuard()));

        assertThat(result.materialized()).isFalse();
        assertThat(result.blockingReasons()).contains("at-least-one-official-source");
    }

    @Test
    void candidateWithoutLegalReferenceIsBlocked() {
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate("Resposta técnica.", List.of(officialSource()), List.of(), passedGuard()));

        assertThat(result.materialized()).isFalse();
        assertThat(result.blockingReasons()).contains("at-least-one-legal-reference");
    }

    @Test
    void candidateWithFailedGuardResultIsBlocked() {
        AtFaqGovernedPublicationGuardResult failed =
                new AtFaqGovernedPublicationGuardResult(false, List.of(), List.of("some-guard"), List.of(), List.of());
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate("Resposta técnica.", List.of(officialSource()), List.of("Artigo 41.º do CIVA"), failed));

        assertThat(result.materialized()).isFalse();
        assertThat(result.blockingReasons()).contains("guard-result-passed");
    }

    @Test
    void cleanBuiltCandidateIsMaterialized() {
        AtFaqMaterializationItemResult result =
                assembler.assemble(readyCandidate("Resposta técnica.", List.of(officialSource()), List.of("Artigo 41.º do CIVA"), passedGuard()));

        assertThat(result.materialized()).isTrue();
        assertThat(result.published()).isFalse();
        assertThat(result.indexed()).isFalse();
        assertThat(result.persisted()).isFalse();
        assertThat(result.knowledgeQaId()).isNull();
        assertThat(result.draft().intendedCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
    }

    // --- builders -----------------------------------------------------------

    private static AtFaqGovernedPublicationGuardResult passedGuard() {
        return new AtFaqGovernedPublicationGuardResult(true, List.of("all-guards"), List.of(), List.of(), List.of());
    }

    private static AtFaqPreCurationSourceCandidate officialSource() {
        return new AtFaqPreCurationSourceCandidate(
                KnowledgeSourceType.OFFICIAL_FAQ, "FAQ AT", "https://info.portaldasfinancas.gov.pt/faq",
                "Artigo 41.º do CIVA", null, null, null, null, null, null, true, true, List.of());
    }

    private static AtFaqPreCurationSourceCandidate nonOfficialSource() {
        return new AtFaqPreCurationSourceCandidate(
                KnowledgeSourceType.OTHER, "Blog fiscal", "https://exemplo.pt/artigo",
                null, null, null, null, null, null, null, false, false, List.of());
    }

    private static AtFaqGovernedPublicationCandidate readyCandidate(
            String technicalAnswer,
            List<AtFaqPreCurationSourceCandidate> sources,
            List<String> legalReferences,
            AtFaqGovernedPublicationGuardResult guard) {
        return new AtFaqGovernedPublicationCandidate(
                "AT-FAQ-BUILT",
                "Qual o prazo?",
                AtFaqBatchPublicationPath.AUTO_CONTROLLED,
                AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE,
                AtFaqBatchPublicationPath.AUTO_CONTROLLED,
                AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION,
                true, false, false, false, false,
                "Resposta curta.",
                technicalAnswer,
                KnowledgeTopic.IVA,
                "prazos",
                "PT",
                KnowledgeRiskLevel.LOW,
                sources,
                legalReferences,
                List.of("OFFICIAL_FAQ — FAQ AT"),
                guard,
                List.of());
    }
}
