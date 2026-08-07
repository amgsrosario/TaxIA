package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.List;

/**
 * A curable Q&A <b>draft</b> assembled from a {@code READY_FOR_FUTURE_PUBLICATION} plan candidate
 * (Bloco E — E8A).
 *
 * <p>"Materializar conhecimento não é publicá-lo. É preparar o objecto que poderá ser publicado."
 * This is the in-memory object that could later become a persisted {@code KnowledgeQuestionAnswer},
 * but it is <b>not</b> persisted, <b>not</b> published and <b>not</b> indexed here. It carries the
 * curated fields plus the intended non-published curation status; it never carries raw HTML,
 * chunks, prompts, embeddings or fetched content.
 *
 * <p>{@code intendedCurationStatus} is {@link KnowledgeCurationStatus#IMPORTED} — the most
 * conservative existing status. The domain has no {@code PRE_CURATED}/{@code DRAFT} value, and
 * only {@link KnowledgeCurationStatus#VALIDATED} feeds the RAG, so a draft can never be
 * RAG-eligible.
 *
 * @param externalId       source id of the item
 * @param normalizedQuestion whitespace-stable question
 * @param shortAnswer      curated short answer
 * @param technicalAnswer  verbatim technical answer
 * @param topic            fiscal topic
 * @param subtopic         optional subtopic (may be null)
 * @param jurisdiction     jurisdiction ("PT")
 * @param riskLevel        editorial risk level
 * @param intendedCurationStatus the non-published status the draft would carry (IMPORTED)
 * @param sources          projected source candidates (at least one, at least one official)
 * @param legalReferences  legal references backing the answer (at least one)
 * @param warnings         non-blocking notes
 * @param blockingReasons  reasons the draft could not be assembled (empty when materialized)
 */
public record AtFaqMaterializationCandidate(
        String externalId,
        String normalizedQuestion,
        String shortAnswer,
        String technicalAnswer,
        KnowledgeTopic topic,
        String subtopic,
        String jurisdiction,
        KnowledgeRiskLevel riskLevel,
        KnowledgeCurationStatus intendedCurationStatus,
        List<AtFaqMaterializationSourceCandidate> sources,
        List<String> legalReferences,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqMaterializationCandidate {
        sources = sources == null ? List.of() : List.copyOf(sources);
        legalReferences = legalReferences == null ? List.of() : List.copyOf(legalReferences);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }
}
