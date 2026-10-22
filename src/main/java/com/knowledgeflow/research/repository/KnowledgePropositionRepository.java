package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.KnowledgeProposition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgePropositionRepository extends JpaRepository<KnowledgeProposition, UUID> {

    List<KnowledgeProposition> findByInvestigationIdOrderByCreatedAtAsc(UUID investigationId);
}
