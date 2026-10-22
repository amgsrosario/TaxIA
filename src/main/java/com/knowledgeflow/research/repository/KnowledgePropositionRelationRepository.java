package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.KnowledgePropositionRelation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgePropositionRelationRepository extends JpaRepository<KnowledgePropositionRelation, UUID> {

    List<KnowledgePropositionRelation> findByFromPropositionIdIn(Collection<UUID> propositionIds);
}
