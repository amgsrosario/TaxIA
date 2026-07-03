package com.knowledgeflow.ingestion.atfaq;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AtFaqIngestionRunRepository extends JpaRepository<AtFaqIngestionRun, UUID> {

    Optional<AtFaqIngestionRun> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
