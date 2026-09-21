package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the governed rollback taxonomy (Bloco E — E10-policy-impl, decision D4).
 *
 * <p>Proves the closed {@link AtFaqRollbackReason} taxonomy and the {@link AtFaqRollbackMotive}
 * validation/rendering contract that replaces the previous free-text reason:
 * <ul>
 *   <li>a {@code null} reasonCode is rejected (the code is mandatory);</li>
 *   <li>{@link AtFaqRollbackReason#OTHER} without a detail — including whitespace-only — is rejected;</li>
 *   <li>{@code OTHER} with a real detail is accepted;</li>
 *   <li>a normal reason without a detail is accepted;</li>
 *   <li>{@code SOURCE_OUTDATED} and {@code LEGAL_CHANGE} remain distinct values;</li>
 *   <li>{@link AtFaqRollbackMotive#auditDetail()} renders the stable {@code reasonCode=...} /
 *       {@code reasonDetail=...} string persisted in the audit log.</li>
 * </ul>
 * Pure unit test: no Spring context, no database.
 */
class AtFaqRollbackMotiveTest {

    // --- PASSO 7 case 1: reasonCode null → rejected -------------------------------------------

    @Test
    void nullReasonCodeIsRejected() {
        assertThatThrownBy(() -> AtFaqRollbackMotive.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> new AtFaqRollbackMotive(null, "qualquer detalhe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- PASSO 7 case 2: OTHER without detail → rejected --------------------------------------

    @Test
    void otherWithoutDetailIsRejected() {
        assertThatThrownBy(() -> AtFaqRollbackMotive.of(AtFaqRollbackReason.OTHER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTHER");
        assertThatThrownBy(() -> AtFaqRollbackMotive.of(AtFaqRollbackReason.OTHER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- PASSO 7 case 3: OTHER with detail → accepted ----------------------------------------

    @Test
    void otherWithDetailIsAccepted() {
        AtFaqRollbackMotive motive =
                AtFaqRollbackMotive.of(AtFaqRollbackReason.OTHER, "motivo específico não taxonomizado");

        assertThat(motive.code()).isEqualTo(AtFaqRollbackReason.OTHER);
        assertThat(motive.hasDetail()).isTrue();
        assertThat(motive.detail()).isEqualTo("motivo específico não taxonomizado");
        assertThat(motive.auditDetail())
                .isEqualTo("reasonCode=OTHER; reasonDetail=motivo específico não taxonomizado");
    }

    // --- PASSO 7 case 4: normal reason without detail → accepted ------------------------------

    @Test
    void normalReasonWithoutDetailIsAccepted() {
        AtFaqRollbackMotive motive = AtFaqRollbackMotive.of(AtFaqRollbackReason.PUBLICATION_ERROR);

        assertThat(motive.code()).isEqualTo(AtFaqRollbackReason.PUBLICATION_ERROR);
        assertThat(motive.hasDetail()).isFalse();
        assertThat(motive.detail()).isNull();
        assertThat(motive.auditDetail()).isEqualTo("reasonCode=PUBLICATION_ERROR");
    }

    // --- PASSO 7 case 5: whitespace-only detail for OTHER → rejected --------------------------

    @Test
    void whitespaceOnlyDetailForOtherIsRejected() {
        assertThatThrownBy(() -> AtFaqRollbackMotive.of(AtFaqRollbackReason.OTHER, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTHER");
    }

    @Test
    void whitespaceOnlyDetailForNormalReasonIsNormalizedToNull() {
        AtFaqRollbackMotive motive = AtFaqRollbackMotive.of(AtFaqRollbackReason.LEGAL_CHANGE, "   ");

        assertThat(motive.hasDetail()).isFalse();
        assertThat(motive.detail()).isNull();
        assertThat(motive.auditDetail()).isEqualTo("reasonCode=LEGAL_CHANGE");
    }

    @Test
    void detailIsTrimmedBeforePersisting() {
        AtFaqRollbackMotive motive =
                AtFaqRollbackMotive.of(AtFaqRollbackReason.LEGAL_CHANGE, "  alteração ao CIVA  ");

        assertThat(motive.detail()).isEqualTo("alteração ao CIVA");
        assertThat(motive.auditDetail()).isEqualTo("reasonCode=LEGAL_CHANGE; reasonDetail=alteração ao CIVA");
    }

    // --- PASSO 7 case 6: SOURCE_OUTDATED and LEGAL_CHANGE stay distinct -----------------------

    @Test
    void sourceOutdatedAndLegalChangeAreDistinctValues() {
        assertThat(AtFaqRollbackReason.SOURCE_OUTDATED)
                .isNotEqualTo(AtFaqRollbackReason.LEGAL_CHANGE);
        assertThat(AtFaqRollbackReason.SOURCE_OUTDATED.name()).isEqualTo("SOURCE_OUTDATED");
        assertThat(AtFaqRollbackReason.LEGAL_CHANGE.name()).isEqualTo("LEGAL_CHANGE");
        // Governance semantics differ: a material legal change is flagged graver; a stale source is not.
        assertThat(AtFaqRollbackReason.LEGAL_CHANGE.higherSeverity()).isTrue();
        assertThat(AtFaqRollbackReason.SOURCE_OUTDATED.higherSeverity()).isFalse();
    }

    // --- Taxonomy invariants ------------------------------------------------------------------

    @Test
    void onlyOtherRequiresDetail() {
        for (AtFaqRollbackReason reason : AtFaqRollbackReason.values()) {
            if (reason == AtFaqRollbackReason.OTHER) {
                assertThat(reason.requiresDetail())
                        .as("OTHER must require a complementary detail").isTrue();
            } else {
                assertThat(reason.requiresDetail())
                        .as("%s must not require a complementary detail", reason).isFalse();
            }
        }
    }

    @Test
    void graverReasonsAreFlaggedHigherSeverityButNeverAuthorizeAutomation() {
        // The four graver reasons are governance signals only — no automation is wired to the flag.
        assertThat(AtFaqRollbackReason.LEGAL_CHANGE.higherSeverity()).isTrue();
        assertThat(AtFaqRollbackReason.SECURITY_OR_COMPLIANCE.higherSeverity()).isTrue();
        assertThat(AtFaqRollbackReason.CONTENT_ERROR.higherSeverity()).isTrue();
        assertThat(AtFaqRollbackReason.SOURCE_INVALIDATED.higherSeverity()).isTrue();

        assertThat(AtFaqRollbackReason.CURATION_ERROR.higherSeverity()).isFalse();
        assertThat(AtFaqRollbackReason.DUPLICATE_OR_SUPERSEDED.higherSeverity()).isFalse();
        assertThat(AtFaqRollbackReason.PUBLICATION_ERROR.higherSeverity()).isFalse();
        assertThat(AtFaqRollbackReason.MANUAL_CORRECTION.higherSeverity()).isFalse();
    }

    @Test
    void taxonomyHasExactlyTheTenApprovedValues() {
        assertThat(AtFaqRollbackReason.values()).containsExactlyInAnyOrder(
                AtFaqRollbackReason.SOURCE_INVALIDATED,
                AtFaqRollbackReason.SOURCE_OUTDATED,
                AtFaqRollbackReason.CONTENT_ERROR,
                AtFaqRollbackReason.CURATION_ERROR,
                AtFaqRollbackReason.DUPLICATE_OR_SUPERSEDED,
                AtFaqRollbackReason.LEGAL_CHANGE,
                AtFaqRollbackReason.PUBLICATION_ERROR,
                AtFaqRollbackReason.SECURITY_OR_COMPLIANCE,
                AtFaqRollbackReason.MANUAL_CORRECTION,
                AtFaqRollbackReason.OTHER);
    }
}
