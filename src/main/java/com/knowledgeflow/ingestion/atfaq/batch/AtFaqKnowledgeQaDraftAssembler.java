package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure assembler that turns a {@code READY_FOR_FUTURE_PUBLICATION} plan candidate into a curable
 * {@link AtFaqMaterializationCandidate} draft (Bloco E — E8A).
 *
 * <p>"Materializar conhecimento não é publicá-lo. É preparar o objecto que poderá ser publicado."
 * This class is deterministic and side-effect free: it never touches a database, never calls a
 * URL, never invokes an LLM, never publishes and never indexes. It only validates the minimal
 * fields a future {@code KnowledgeQuestionAnswer} would need and projects them into an in-memory
 * draft. When a guard fails, it returns a non-materialized {@link AtFaqMaterializationItemResult}
 * describing why.
 *
 * <p>Every produced draft carries {@link KnowledgeCurationStatus#IMPORTED} — the most conservative
 * existing status. The domain has no {@code PRE_CURATED}/{@code DRAFT} value and only
 * {@link KnowledgeCurationStatus#VALIDATED} feeds the RAG, so a draft is never RAG-eligible.
 */
public class AtFaqKnowledgeQaDraftAssembler {

    /** The intended, deliberately non-published curation status for every assembled draft. */
    static final KnowledgeCurationStatus DRAFT_CURATION_STATUS = KnowledgeCurationStatus.IMPORTED;

    /**
     * Assembles a draft from a plan candidate. The candidate is expected to be
     * {@code READY_FOR_FUTURE_PUBLICATION}; the minimal guards are re-checked defensively so the
     * assembler is safe to call in isolation.
     */
    public AtFaqMaterializationItemResult assemble(AtFaqGovernedPublicationCandidate candidate) {
        List<String> blocking = new ArrayList<>();

        if (candidate == null) {
            return new AtFaqMaterializationItemResult(
                    null, false, false, false, false, null, null, null,
                    List.of(), List.of("candidate-present"), List.of("Candidato ausente."));
        }

        gate(blocking, "readiness-ready-for-future-publication",
                candidate.readiness() == AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION);
        gate(blocking, "ready-for-future-publication-flag", candidate.readyForFuturePublication());
        gate(blocking, "guard-result-passed",
                candidate.guardResult() != null && candidate.guardResult().passed());
        gate(blocking, "normalized-question-present", isNotBlank(candidate.normalizedQuestion()));
        gate(blocking, "short-answer-present", isNotBlank(candidate.proposedShortAnswer()));
        gate(blocking, "technical-answer-present", isNotBlank(candidate.proposedTechnicalAnswer()));
        gate(blocking, "topic-present", candidate.proposedTopic() != null);
        gate(blocking, "risk-level-present", candidate.proposedRiskLevel() != null);
        gate(blocking, "jurisdiction-present", isNotBlank(candidate.proposedJurisdiction()));
        gate(blocking, "at-least-one-source", !candidate.proposedSources().isEmpty());
        gate(blocking, "at-least-one-official-source",
                candidate.proposedSources().stream().anyMatch(AtFaqPreCurationSourceCandidate::official));
        gate(blocking, "at-least-one-legal-reference", !candidate.proposedLegalReferences().isEmpty());

        if (!blocking.isEmpty()) {
            return new AtFaqMaterializationItemResult(
                    candidate.externalId(), false, false, false, false, null,
                    candidate.normalizedQuestion(), null,
                    List.of(), List.copyOf(blocking),
                    List.of("Corrigir campos em falta antes de materializar rascunho."));
        }

        AtFaqMaterializationCandidate draft = new AtFaqMaterializationCandidate(
                candidate.externalId(),
                candidate.normalizedQuestion().strip(),
                candidate.proposedShortAnswer().strip(),
                candidate.proposedTechnicalAnswer().strip(),
                candidate.proposedTopic(),
                candidate.proposedSubtopic(),
                candidate.proposedJurisdiction().strip(),
                candidate.proposedRiskLevel(),
                DRAFT_CURATION_STATUS,
                projectSources(candidate.proposedSources()),
                List.copyOf(candidate.proposedLegalReferences()),
                List.of(),
                List.of());

        return new AtFaqMaterializationItemResult(
                candidate.externalId(),
                true /* materialized */,
                false /* persisted */,
                false /* published */,
                false /* indexed */,
                null /* knowledgeQaId — not persisted */,
                draft.normalizedQuestion(),
                draft,
                List.of(),
                List.of(),
                List.of("Rascunho pronto para publicação governada futura (E8B). Ainda não publicado nem indexado."));
    }

    private static List<AtFaqMaterializationSourceCandidate> projectSources(
            List<AtFaqPreCurationSourceCandidate> sources) {
        List<AtFaqMaterializationSourceCandidate> projected = new ArrayList<>(sources.size());
        for (AtFaqPreCurationSourceCandidate s : sources) {
            projected.add(new AtFaqMaterializationSourceCandidate(
                    s.type(),
                    s.title(),
                    s.url(),
                    s.legalReference(),
                    s.official(),
                    s.primary(),
                    s.warnings()));
        }
        return projected;
    }

    private static void gate(List<String> blocking, String guard, boolean ok) {
        if (!ok) {
            blocking.add(guard);
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
