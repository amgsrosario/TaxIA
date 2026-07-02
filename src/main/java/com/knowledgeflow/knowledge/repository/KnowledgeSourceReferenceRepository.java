package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSourceReferenceRepository extends JpaRepository<KnowledgeSourceReference, UUID> {

    List<KnowledgeSourceReference> findByQuestionAnswerId(UUID questionAnswerId);

    long countByQuestionAnswerId(UUID questionAnswerId);

    void deleteByQuestionAnswerId(UUID questionAnswerId);
}
