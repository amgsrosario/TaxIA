package com.knowledgeflow.research.repository;

import com.knowledgeflow.research.entity.CriticalMemoryHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriticalMemoryHistoryRepository extends JpaRepository<CriticalMemoryHistory, UUID> {

    List<CriticalMemoryHistory> findByEntryIdOrderByOccurredAtAsc(UUID entryId);

    List<CriticalMemoryHistory> findByInvestigationIdOrderByOccurredAtAsc(UUID investigationId);
}
