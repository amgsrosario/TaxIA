package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.ResearchEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchEvidenceRepository extends JpaRepository<ResearchEvidence, UUID> {

    List<ResearchEvidence> findByInvestigationIdOrderByCreatedAtAsc(UUID investigationId);
}
