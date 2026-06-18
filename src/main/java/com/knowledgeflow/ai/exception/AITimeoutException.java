package com.knowledgeflow.ai.exception;

public class AITimeoutException extends AIProviderException {

    public AITimeoutException(String message) {
        super(message);
    }

    public AITimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
