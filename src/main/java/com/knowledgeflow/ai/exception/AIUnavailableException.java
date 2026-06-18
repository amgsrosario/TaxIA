package com.knowledgeflow.ai.exception;

public class AIUnavailableException extends AIProviderException {

    public AIUnavailableException(String message) {
        super(message);
    }

    public AIUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
