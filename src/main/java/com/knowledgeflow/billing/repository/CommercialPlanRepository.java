package com.knowledgeflow.billing.repository;

import com.knowledgeflow.billing.entity.CommercialPlan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialPlanRepository extends JpaRepository<CommercialPlan, UUID> {
}
