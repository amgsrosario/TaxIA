package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeSourceReferenceRepository extends JpaRepository<KnowledgeSourceReference, UUID> {

    List<KnowledgeSourceReference> findByQuestionAnswerId(UUID questionAnswerId);

    long countByQuestionAnswerId(UUID questionAnswerId);

    /**
     * Fontes curadas de várias Q&amp;A numa única query, como linhas só de leitura (sem carregar
     * entidades nem a relação lazy com a Q&amp;A). A ordem não é garantida.
     */
    @Query("""
            select new com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow(
                s.questionAnswer.id, s.id, s.sourceType, s.title, s.legalReference, s.url, s.createdAt)
            from KnowledgeSourceReference s
            where s.questionAnswer.id in :questionAnswerIds
            """)
    List<KnowledgeSourceReferenceRow> findRowsByQuestionAnswerIdIn(
            @Param("questionAnswerIds") Collection<UUID> questionAnswerIds);

    void deleteByQuestionAnswerId(UUID questionAnswerId);
}
