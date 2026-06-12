package com.knowledgeflow.cases.repository;

import com.knowledgeflow.cases.entity.KnowledgeCaseVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeCaseVersionRepository extends JpaRepository<KnowledgeCaseVersion, UUID> {

    @EntityGraph(attributePaths = {"knowledgeCase", "createdByUser"})
    List<KnowledgeCaseVersion> findByKnowledgeCaseIdOrderByVersionNumberAsc(UUID knowledgeCaseId);
}
