package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ai.documented.SourceDiversity;
import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
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
 * Tests for the controlled AT-FAQ pre-curation (Bloco E — E5).
 *
 * <p>Proves that pre-curation produces deterministic proposals, never publishes/indexes, never
 * invents a technical answer, and only escalates (never relaxes) the E4 publication path. Pure
 * unit test: no Spring context, no database, no HTTP, no LLM.
 */
class AtFaqPreCurationServiceTest {

    private AtFaqPreCurationService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-06T10:15:30Z"), ZoneOffset.UTC);
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, fixedClock);
        service = new AtFaqPreCurationService(batchService, normalizer, fixedClock);
    }

    private static AtFaqPreCuratedBatchItem itemOf(AtFaqPreCurationResult result, String externalId) {
        return result.items().stream()
                .filter(i -> externalId.equals(i.externalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No pre-curated item for " + externalId));
    }

    @Test
    void producesResultForControlledBatch() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());

        assertThat(result.batchId()).startsWith("atfaq-batch-");
        assertThat(result.items()).hasSize(6);
        assertThat(result.totals().totalItems()).isEqualTo(6);
        assertThat(result.totals().preCurated()).isEqualTo(6);
        assertThat(result.nextActions()).isNotEmpty();
    }

    @Test
    void cleanItemStaysAutoControlled() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem clean = itemOf(result, "AT-FAQ-1001");

        assertThat(clean.proposedPublicationPath()).isEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(clean.eligibleForAutoControlledCandidate()).isTrue();
        assertThat(clean.requiredReviewReason()).isNull();
        assertThat(clean.proposedTopic()).isEqualTo(KnowledgeTopic.IVA);
        assertThat(clean.proposedJurisdiction()).isEqualTo("PT");
        assertThat(clean.proposedFreshnessStatus()).isEqualTo(FreshnessStatus.CURRENT);
        assertThat(clean.proposedLegalReferences()).isNotEmpty();
        assertThat(clean.proposedShortAnswer()).isNotBlank();
    }

    @Test
    void noLegalFoundationItemIsAssistedWithReviewReason() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem item = itemOf(result, "AT-FAQ-1002");

        assertThat(item.proposedPublicationPath()).isEqualTo(AtFaqBatchPublicationPath.ASSISTED);
        assertThat(item.proposedLegalReferences()).isEmpty();
        assertThat(item.requiredReviewReason()).isNotNull();
        assertThat(item.eligibleForAutoControlledCandidate()).isFalse();
    }

    @Test
    void highRiskItemIsManualRequired() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem item = itemOf(result, "AT-FAQ-1005");

        assertThat(item.proposedPublicationPath()).isEqualTo(AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        assertThat(item.proposedRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
        assertThat(item.requiredReviewReason()).isNotNull();
    }

    @Test
    void noTechnicalAnswerItemIsNotPublishableAndAnswerNotInvented() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem item = itemOf(result, "AT-FAQ-1006");

        assertThat(item.proposedPublicationPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        assertThat(item.proposedTechnicalAnswer()).isNull(); // never invented
        assertThat(item.warnings()).anyMatch(w -> w.toLowerCase().contains("resposta técnica"));
    }

    @Test
    void duplicateItemIsNeverAutoControlled() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem duplicate = itemOf(result, "AT-FAQ-1003");

        assertThat(duplicate.proposedPublicationPath()).isNotEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(duplicate.duplicateCandidates()).contains("AT-FAQ-1001");
    }

    @Test
    void conflictItemIsNeverAutoControlled() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem conflict = itemOf(result, "AT-FAQ-1004");

        assertThat(conflict.proposedPublicationPath()).isNotEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(conflict.conflictCandidates()).contains("explicit-conflict-marker");
        assertThat(conflict.requiredReviewReason()).isNotNull();
    }

    @Test
    void freshnessIsUncertainByDefault() {
        // Item with no explicit freshness marker → proposed freshness UNCERTAIN.
        AtFaqControlledBatchItem noMarker = new AtFaqControlledBatchItem(
                "AT-FAQ-NOFRESH", "faq://at/local/nofresh", "FAQ AT — sem marcador",
                "Qual o prazo geral de reclamação graciosa?",
                "O prazo é o previsto na lei aplicável.",
                "Prazo previsto na lei; carece de confirmação.",
                "PROCEDIMENTO_TRIBUTARIO", KnowledgeRiskLevel.LOW, "Artigo 70.º do CPPT",
                true, null /* no freshness marker */, false, false);

        AtFaqPreCurationResult result = service.preCurate(List.of(noMarker));
        AtFaqPreCuratedBatchItem item = itemOf(result, "AT-FAQ-NOFRESH");

        assertThat(item.proposedFreshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    @Test
    void outdatedOnlyWithExplicitMarker() {
        AtFaqPreCurationResult result =
                service.preCurate(List.of(ControlledBatchFixtures.outdatedMarked()));
        AtFaqPreCuratedBatchItem item = itemOf(result, "AT-FAQ-1007");

        assertThat(item.proposedFreshnessStatus()).isEqualTo(FreshnessStatus.OUTDATED);
        // OUTDATED escalates the path to at least MANUAL_REQUIRED.
        assertThat(item.proposedPublicationPath()).isIn(
                AtFaqBatchPublicationPath.MANUAL_REQUIRED, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
    }

    @Test
    void currentOnlyWithExplicitMarker() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem clean = itemOf(result, "AT-FAQ-1001");

        assertThat(clean.proposedFreshnessStatus()).isEqualTo(FreshnessStatus.CURRENT);
    }

    @Test
    void faqSourceCandidateIsAlwaysCreated() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem clean = itemOf(result, "AT-FAQ-1001");

        assertThat(clean.proposedSources())
                .anyMatch(s -> s.type() == KnowledgeSourceType.OFFICIAL_FAQ && "faq://at/local/iva-periodicidade-mensal".equals(s.url()));
    }

    @Test
    void legalSourceCandidateCreatedWhenLegalReferencePresent() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem clean = itemOf(result, "AT-FAQ-1001");

        assertThat(clean.proposedSources())
                .anyMatch(s -> s.type() == KnowledgeSourceType.LEGISLATION && s.primary());
    }

    @Test
    void sourceDiversityIsMaterialWhenFaqAndLegislationCoexist() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCuratedBatchItem clean = itemOf(result, "AT-FAQ-1001");

        assertThat(clean.proposedSources())
                .allMatch(s -> s.sourceDiversity() == SourceDiversity.MATERIAL_DIVERSITY);
    }

    @Test
    void resultNeverPublishesOrIndexesAndHasNoRawContent() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());

        // No published/indexed fields exist on the E5 records at all (compile-time guarantee).
        for (AtFaqPreCuratedBatchItem item : result.items()) {
            List<String> texts = new ArrayList<>();
            texts.add(item.normalizedQuestion());
            texts.add(item.proposedShortAnswer());
            texts.add(item.proposedTechnicalAnswer());
            texts.addAll(item.proposedLegalReferences());
            for (AtFaqPreCurationSourceCandidate s : item.proposedSources()) {
                texts.add(s.title());
            }
            for (String t : texts) {
                if (t != null) {
                    assertThat(t).doesNotContain("<").doesNotContain("```");
                    assertThat(t.toLowerCase()).doesNotContain("prompt");
                }
            }
        }
    }

    @Test
    void executionIsDeterministic() {
        AtFaqPreCurationResult first = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqPreCurationResult second = service.preCurate(ControlledBatchFixtures.sixItemBatch());

        assertThat(second).isEqualTo(first);
        assertThat(second.batchId()).isEqualTo(first.batchId());
        assertThat(second.items()).isEqualTo(first.items());
    }

    @Test
    void totalsAreConsistentWithItems() {
        AtFaqPreCurationResult result = service.preCurate(ControlledBatchFixtures.sixItemBatch());
        var t = result.totals();

        assertThat(t.autoControlledCandidates()
                + t.assistedCandidates()
                + t.manualRequiredCandidates()
                + t.notPublishable())
                .isEqualTo(t.totalItems());
        assertThat(t.requiringReview())
                .isEqualTo((int) result.items().stream().filter(i -> i.requiredReviewReason() != null).count());
    }
}
