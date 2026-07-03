package com.knowledgeflow.ingestion.atfaq;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AtFaqPageSnapshotRepository extends JpaRepository<AtFaqPageSnapshot, UUID> {

    Optional<AtFaqPageSnapshot> findByOrganizationIdAndUrl(UUID organizationId, String url);
}
