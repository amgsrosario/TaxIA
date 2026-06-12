package com.knowledgeflow.clients.exception;

import com.knowledgeflow.common.error.ResourceNotFoundException;
import java.util.UUID;

public class ClientNotFoundException extends ResourceNotFoundException {

    public ClientNotFoundException(UUID clientId) {
        super("Client was not found: " + clientId);
    }
}
