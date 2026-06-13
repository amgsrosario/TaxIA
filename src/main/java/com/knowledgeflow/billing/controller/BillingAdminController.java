package com.knowledgeflow.billing.controller;

import com.knowledgeflow.billing.dto.AssignPlanRequest;
import com.knowledgeflow.billing.dto.CommercialPlanCreateRequest;
import com.knowledgeflow.billing.dto.CommercialPlanResponse;
import com.knowledgeflow.billing.dto.ConsumptionSummaryResponse;
import com.knowledgeflow.billing.dto.OrganizationPlanResponse;
import com.knowledgeflow.billing.service.CommercialPlanService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingAdminController {

    private final CommercialPlanService commercialPlanService;

    public BillingAdminController(CommercialPlanService commercialPlanService) {
        this.commercialPlanService = commercialPlanService;
    }

    // -------------------------------------------------------------------------
    // Plan catalogue (ADMIN only — internal operation)
    // -------------------------------------------------------------------------

    @PostMapping("/plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CommercialPlanResponse> createPlan(
            @Valid @RequestBody CommercialPlanCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commercialPlanService.createPlan(request));
    }

    @GetMapping("/plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<CommercialPlanResponse>> listPlans() {
        return ResponseEntity.ok(commercialPlanService.listPlans());
    }

    @GetMapping("/plans/{planId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CommercialPlanResponse> getPlan(@PathVariable UUID planId) {
        return ResponseEntity.ok(commercialPlanService.getPlan(planId));
    }

    // -------------------------------------------------------------------------
    // Organisation plan assignment & consumption
    // -------------------------------------------------------------------------

    @PostMapping("/organizations/{organizationId}/plan")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrganizationPlanResponse> assignPlan(
            @PathVariable UUID organizationId,
            @Valid @RequestBody AssignPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commercialPlanService.assignPlan(organizationId, request));
    }

    @GetMapping("/organizations/{organizationId}/plan")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrganizationPlanResponse> getActivePlan(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok(commercialPlanService.getActivePlan(organizationId));
    }

    @GetMapping("/organizations/{organizationId}/consumption")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConsumptionSummaryResponse> getConsumption(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok(commercialPlanService.getConsumptionSummary(organizationId));
    }
}
