package com.knowledgeflow.billing.repository;

import com.knowledgeflow.billing.entity.OrganizationPlan;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationPlanRepository extends JpaRepository<OrganizationPlan, UUID> {

    /**
     * Returns the active plan for an organization at a given instant.
     * A plan is active when startsAt <= now AND (endsAt IS NULL OR endsAt > now).
     */
    @Query("""
            SELECT op FROM OrganizationPlan op
            WHERE op.organizationId = :organizationId
              AND op.startsAt <= :now
              AND (op.endsAt IS NULL OR op.endsAt > :now)
            ORDER BY op.startsAt DESC
            LIMIT 1
            """)
    Optional<OrganizationPlan> findActivePlan(
            @Param("organizationId") UUID organizationId,
            @Param("now") Instant now
    );
}
