package com.knowledgeflow.billing.service;

import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.ConsumptionEvent;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.ConsumptionEventType;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards billable actions behind the active commercial plan.
 * All check methods must be called inside an active @Transactional context
 * so that the guard and the consumption event are committed atomically.
 */
@Service
public class EntitlementService {

    private final OrganizationPlanRepository organizationPlanRepository;
    private final ConsumptionEventRepository consumptionEventRepository;

    public EntitlementService(
            OrganizationPlanRepository organizationPlanRepository,
            ConsumptionEventRepository consumptionEventRepository
    ) {
        this.organizationPlanRepository = organizationPlanRepository;
        this.consumptionEventRepository = consumptionEventRepository;
    }

    /**
     * Checks whether the organization may create a new KnowledgeCase and
     * records a KNOWLEDGE_CASE_CREATED consumption event.
     *
     * @param organizationId the organization attempting the action
     * @param entityId       the ID of the case being created (pre-assigned)
     * @throws BusinessException if no active plan or case limit exceeded
     */
    @Transactional
    public void checkAndRecordCaseCreation(UUID organizationId, UUID entityId) {
        OrganizationPlan orgPlan = requireActivePlan(organizationId);
        CommercialPlan plan = orgPlan.getPlan();

        if (plan.getMaxCases() != null) {
            long used = consumptionEventRepository.countByOrganizationIdAndEventTypeAndOccurredAtBetween(
                    organizationId,
                    ConsumptionEventType.KNOWLEDGE_CASE_CREATED,
                    orgPlan.getStartsAt(),
                    orgPlan.getEndsAt() != null ? orgPlan.getEndsAt() : Instant.now().plusSeconds(1)
            );
            if (used >= plan.getMaxCases()) {
                throw new BusinessException(ApiErrorCode.FORBIDDEN,
                        "Case limit reached for current plan (%d of %d used)".formatted(used, plan.getMaxCases()));
            }
        }

        consumptionEventRepository.save(
                new ConsumptionEvent(organizationId, ConsumptionEventType.KNOWLEDGE_CASE_CREATED, entityId));
    }

    /**
     * Checks whether the organization may trigger an AI interaction and
     * records an AI_INTERACTION consumption event.
     *
     * @param organizationId the organization attempting the action
     * @param entityId       the ID of the entity (case/session) this interaction belongs to
     * @throws BusinessException if no active plan or interaction limit exceeded
     */
    @Transactional
    public void checkAndRecordInteraction(UUID organizationId, UUID entityId) {
        OrganizationPlan orgPlan = requireActivePlan(organizationId);
        CommercialPlan plan = orgPlan.getPlan();

        if (plan.getMaxInteractions() != null) {
            long used = consumptionEventRepository.countByOrganizationIdAndEventTypeAndOccurredAtBetween(
                    organizationId,
                    ConsumptionEventType.AI_INTERACTION,
                    orgPlan.getStartsAt(),
                    orgPlan.getEndsAt() != null ? orgPlan.getEndsAt() : Instant.now().plusSeconds(1)
            );
            if (used >= plan.getMaxInteractions()) {
                throw new BusinessException(ApiErrorCode.FORBIDDEN,
                        "Interaction limit reached for current plan (%d of %d used)".formatted(used, plan.getMaxInteractions()));
            }
        }

        consumptionEventRepository.save(
                new ConsumptionEvent(organizationId, ConsumptionEventType.AI_INTERACTION, entityId));
    }

    // -------------------------------------------------------------------------

    private OrganizationPlan requireActivePlan(UUID organizationId) {
        return organizationPlanRepository.findActivePlan(organizationId, Instant.now())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.FORBIDDEN,
                        "No active commercial plan for this organization"));
    }
}
