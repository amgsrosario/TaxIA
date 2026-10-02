package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityExclusion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeQaApplicabilityExclusionRepository
        extends JpaRepository<KnowledgeQaApplicabilityExclusion, UUID> {

    @Query("""
            select e from KnowledgeQaApplicabilityExclusion e
            where e.questionAnswer.id = :qaId
            order by e.createdAt, e.marker
            """)
    List<KnowledgeQaApplicabilityExclusion> findByKnowledgeQaId(@Param("qaId") UUID qaId);

    @Query("""
            select e from KnowledgeQaApplicabilityExclusion e
            where e.questionAnswer.id = :qaId and e.marker = :marker
            """)
    Optional<KnowledgeQaApplicabilityExclusion> findByKnowledgeQaIdAndMarker(
            @Param("qaId") UUID qaId, @Param("marker") String marker);

    /**
     * Marcadores efectivos (incluindo remoções pendentes) das Q&amp;A pedidas, numa só query, para o
     * gate de âmbito. Os ids vêm do RAG, já restrito à organização do pedido.
     */
    @Query("""
            select new com.knowledgeflow.knowledge.repository.KnowledgeQaExclusionMarkerRow(
                e.questionAnswer.id, e.marker)
            from KnowledgeQaApplicabilityExclusion e
            where e.questionAnswer.id in :qaIds
            """)
    List<KnowledgeQaExclusionMarkerRow> findMarkerRowsByKnowledgeQaIdIn(@Param("qaIds") Collection<UUID> qaIds);
}
