package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.enums.CriticalClassification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriticalMemoryEntryRepository extends JpaRepository<CriticalMemoryEntry, UUID> {

    Optional<CriticalMemoryEntry> findByOrganizationIdAndClassificationAndClaimSha256(
            UUID organizationId, CriticalClassification classification, String claimSha256);

    List<CriticalMemoryEntry> findByOrganizationIdAndReferenceKeyInOrderByCreatedAtAsc(
            UUID organizationId, Collection<String> referenceKeys);

    List<CriticalMemoryEntry> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<CriticalMemoryEntry> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
