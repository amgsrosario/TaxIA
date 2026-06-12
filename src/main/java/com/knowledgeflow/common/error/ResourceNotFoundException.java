package com.knowledgeflow.common.error;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(ApiErrorCode.NOT_FOUND, message);
    }
}
