package com.knowledgeflow.billing.service;

import com.knowledgeflow.billing.dto.AssignPlanRequest;
import com.knowledgeflow.billing.dto.CommercialPlanCreateRequest;
import com.knowledgeflow.billing.dto.CommercialPlanResponse;
import com.knowledgeflow.billing.dto.ConsumptionSummaryResponse;
import com.knowledgeflow.billing.dto.OrganizationPlanResponse;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.ConsumptionEventType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommercialPlanService {

    private final CommercialPlanRepository commercialPlanRepository;
    private final OrganizationPlanRepository organizationPlanRepository;
    private final ConsumptionEventRepository consumptionEventRepository;

    public CommercialPlanService(
            CommercialPlanRepository commercialPlanRepository,
            OrganizationPlanRepository organizationPlanRepository,
            ConsumptionEventRepository consumptionEventRepository
    ) {
        this.commercialPlanRepository = commercialPlanRepository;
        this.organizationPlanRepository = organizationPlanRepository;
        this.consumptionEventRepository = consumptionEventRepository;
    }

    @Transactional
    public CommercialPlanResponse createPlan(CommercialPlanCreateRequest request) {
        CommercialPlan plan = commercialPlanRepository.save(new CommercialPlan(
                request.name(), request.planType(),
                request.maxCases(), request.maxInteractions(), request.maxPortalUsers()
        ));
        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public List<CommercialPlanResponse> listPlans() {
        return commercialPlanRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CommercialPlanResponse getPlan(UUID planId) {
        return toResponse(findPlan(planId));
    }

    @Transactional
    public OrganizationPlanResponse assignPlan(UUID organizationId, AssignPlanRequest request) {
        CommercialPlan plan = findPlan(request.planId());

        if (request.endsAt() != null && !request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "endsAt must be after startsAt");
        }

        OrganizationPlan orgPlan = organizationPlanRepository.save(
                new OrganizationPlan(organizationId, plan, request.startsAt(), request.endsAt()));

        return toOrgPlanResponse(orgPlan);
    }

    @Transactional(readOnly = true)
    public OrganizationPlanResponse getActivePlan(UUID organizationId) {
        OrganizationPlan orgPlan = organizationPlanRepository
                .findActivePlan(organizationId, Instant.now())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "No active plan for organization: " + organizationId));
        return toOrgPlanResponse(orgPlan);
    }

    @Transactional(readOnly = true)
    public ConsumptionSummaryResponse getConsumptionSummary(UUID organizationId) {
        OrganizationPlan orgPlan = organizationPlanRepository
                .findActivePlan(organizationId, Instant.now())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "No active plan for organization: " + organizationId));

        Instant from = orgPlan.getStartsAt();
        Instant to = orgPlan.getEndsAt() != null ? orgPlan.getEndsAt() : Instant.now().plusSeconds(1);

        long cases = consumptionEventRepository.countByOrganizationIdAndEventTypeAndOccurredAtBetween(
                organizationId, ConsumptionEventType.KNOWLEDGE_CASE_CREATED, from, to);
        long interactions = consumptionEventRepository.countByOrganizationIdAndEventTypeAndOccurredAtBetween(
                organizationId, ConsumptionEventType.AI_INTERACTION, from, to);

        return new ConsumptionSummaryResponse(
                organizationId, from, orgPlan.getEndsAt(),
                cases, interactions,
                orgPlan.getPlan().getMaxCases(),
                orgPlan.getPlan().getMaxInteractions()
        );
    }

    // -------------------------------------------------------------------------

    private CommercialPlan findPlan(UUID planId) {
        return commercialPlanRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Commercial plan not found: " + planId));
    }

    private CommercialPlanResponse toResponse(CommercialPlan p) {
        return new CommercialPlanResponse(
                p.getId(), p.getName(), p.getPlanType(),
                p.getMaxCases(), p.getMaxInteractions(), p.getMaxPortalUsers(),
                p.isActive()
        );
    }

    private OrganizationPlanResponse toOrgPlanResponse(OrganizationPlan op) {
        return new OrganizationPlanResponse(
                op.getId(), op.getOrganizationId(),
                toResponse(op.getPlan()),
                op.getStartsAt(), op.getEndsAt(), op.getCreatedAt()
        );
    }
}
