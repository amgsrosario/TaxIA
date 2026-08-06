package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Proposed publication path for a controlled AT-FAQ batch item (Bloco E — E4).
 *
 * <p>This is a <b>proposal about how an item could be published</b>, produced by a
 * controlled in-memory simulation over local fixtures. It is <b>not</b> a publication
 * action: E4 never publishes and never indexes. The path only records the level of
 * governance an item would require before any human/governed publication step.
 *
 * <p>There is no score and no threshold. Classification is criteria-based and, when
 * several criteria apply, the <b>most restrictive path wins</b>:
 * {@code NOT_PUBLISHABLE > MANUAL_REQUIRED > ASSISTED > AUTO_CONTROLLED}.
 *
 * <p>Contrato: docs/taxia-faq-at-ingestion-batch-contract.md (E3), secção 11.
 */
public enum AtFaqBatchPublicationPath {

    /**
     * Could follow an automatic-but-controlled path: official source, low risk,
     * complete technical answer, legal reference present, no duplicate, no conflict,
     * acceptable actuality. Still requires governance before real publication (E7).
     */
    AUTO_CONTROLLED(0),

    /** Needs assisted curation before publication (medium risk, incomplete legal basis, uncertain actuality, non-blocking duplicate). */
    ASSISTED(1),

    /** Needs an explicit human decision / parecer (high or critical risk, conflict, non-official source, outdated actuality, manual marker). */
    MANUAL_REQUIRED(2),

    /** Cannot be published as-is (missing question/answer/technical answer/source, inadequate source, exact duplicate without utility, blocking error). */
    NOT_PUBLISHABLE(3);

    private final int restrictiveness;

    AtFaqBatchPublicationPath(int restrictiveness) {
        this.restrictiveness = restrictiveness;
    }

    /** Higher means stricter. Used to resolve the most-restrictive-wins rule. */
    public int restrictiveness() {
        return restrictiveness;
    }

    /** Returns the stricter of two paths (ties return {@code current}). */
    public static AtFaqBatchPublicationPath mostRestrictive(AtFaqBatchPublicationPath current, AtFaqBatchPublicationPath candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.restrictiveness > current.restrictiveness ? candidate : current;
    }
}
