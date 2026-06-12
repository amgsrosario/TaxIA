package com.knowledgeflow.interactions.enums;

public enum AssistedInteractionStatus {
    /** Session is open and can receive more questions */
    OPEN,
    /** Session was promoted to a formal KnowledgeCase */
    PROMOTED,
    /** Session was closed without promotion */
    CLOSED
}
