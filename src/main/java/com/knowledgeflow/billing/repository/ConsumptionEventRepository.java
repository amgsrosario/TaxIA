package com.knowledgeflow.billing.repository;

import com.knowledgeflow.billing.entity.ConsumptionEvent;
import com.knowledgeflow.billing.enums.ConsumptionEventType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, UUID> {

    long countByOrganizationIdAndEventTypeAndOccurredAtBetween(
            UUID organizationId,
            ConsumptionEventType eventType,
            Instant from,
            Instant to
    );
}
