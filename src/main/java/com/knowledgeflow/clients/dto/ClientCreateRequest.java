package com.knowledgeflow.clients.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientCreateRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 64) String taxIdentifier,
        @Email @Size(max = 254) String contactEmail,
        @Size(max = 64) String phone,
        @Size(max = 5000) String notes
) {
}
