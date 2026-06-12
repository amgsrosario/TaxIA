package com.knowledgeflow.audit.repository;

import com.knowledgeflow.audit.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
            UUID organizationId,
            String entityType,
            UUID entityId
    );
}
