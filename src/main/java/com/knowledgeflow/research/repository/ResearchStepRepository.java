package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.ResearchStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchStepRepository extends JpaRepository<ResearchStep, UUID> {

    List<ResearchStep> findByInvestigationIdOrderByStepOrderAsc(UUID investigationId);
}
