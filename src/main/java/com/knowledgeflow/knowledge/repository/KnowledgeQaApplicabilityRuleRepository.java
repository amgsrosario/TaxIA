package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeQaApplicabilityRuleRepository
        extends JpaRepository<KnowledgeQaApplicabilityRule, UUID> {

    @Query("""
            select e from KnowledgeQaApplicabilityRule e
            where e.questionAnswer.id = :qaId
            order by e.createdAt, e.marker
            """)
    List<KnowledgeQaApplicabilityRule> findByKnowledgeQaId(@Param("qaId") UUID qaId);

    @Query("""
            select e from KnowledgeQaApplicabilityRule e
            where e.questionAnswer.id = :qaId and e.marker = :marker
            """)
    Optional<KnowledgeQaApplicabilityRule> findByKnowledgeQaIdAndMarker(
            @Param("qaId") UUID qaId, @Param("marker") String marker);

    /**
     * Regras efectivas — exclusões e contextos obrigatórios, incluindo remoções pendentes — das Q&amp;A pedidas, numa só query, para o
     * gate de âmbito. Os ids vêm do RAG, já restrito à organização do pedido.
     */
    @Query("""
            select new com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityRuleRow(
                e.questionAnswer.id, e.marker, e.kind)
            from KnowledgeQaApplicabilityRule e
            where e.questionAnswer.id in :qaIds
            """)
    List<KnowledgeQaApplicabilityRuleRow> findRuleRowsByKnowledgeQaIdIn(@Param("qaIds") Collection<UUID> qaIds);
}
