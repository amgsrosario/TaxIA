package com.knowledgeflow.billing.enums;

public enum PlanType {
    /** Single-ticket access — fixed number of cases */
    TICKET,
    /** Monthly subscription with unlimited or high-cap usage */
    MONTHLY,
    /** Pay-per-interaction model */
    INTERACTIONS,
    /** Hybrid: base subscription + overage per interaction */
    HYBRID
}
