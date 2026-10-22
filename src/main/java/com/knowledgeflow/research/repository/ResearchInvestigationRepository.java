package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.ResearchInvestigation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchInvestigationRepository extends JpaRepository<ResearchInvestigation, UUID> {

    Optional<ResearchInvestigation> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
