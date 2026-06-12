package com.knowledgeflow.cases.exception;

import com.knowledgeflow.common.error.ResourceNotFoundException;
import java.util.UUID;

public class KnowledgeCaseNotFoundException extends ResourceNotFoundException {

    public KnowledgeCaseNotFoundException(UUID knowledgeCaseId) {
        super("KnowledgeCase was not found: " + knowledgeCaseId);
    }
}
