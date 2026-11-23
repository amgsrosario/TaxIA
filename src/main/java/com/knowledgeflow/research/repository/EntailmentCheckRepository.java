package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.EntailmentCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntailmentCheckRepository extends JpaRepository<EntailmentCheck, UUID> {

    List<EntailmentCheck> findByInvestigationIdOrderByCreatedAtAsc(UUID investigationId);
}
