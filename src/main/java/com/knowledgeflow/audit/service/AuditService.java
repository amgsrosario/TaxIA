package com.knowledgeflow.audit.service;

import com.knowledgeflow.audit.entity.AuditEvent;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Records an audit event within the current transaction.
     * Must be called inside a @Transactional context — the event is persisted
     * atomically with the action that triggered it.
     */
    public void record(
            UUID organizationId,
            UUID userId,
            AuditAction action,
            String entityType,
            UUID entityId,
            String metadata
    ) {
        auditEventRepository.save(new AuditEvent(
                organizationId,
                userId,
                action,
                entityType,
                entityId,
                metadata
        ));
    }

    public void record(UUID organizationId, UUID userId, AuditAction action, String entityType, UUID entityId) {
        record(organizationId, userId, action, entityType, entityId, null);
    }
}
