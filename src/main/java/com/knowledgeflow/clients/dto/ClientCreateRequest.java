package com.knowledgeflow.clients.dto;

import com.knowledgeflow.clients.enums.ClientCategory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ClientCreateRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 64) String taxIdentifier,
        @Email @Size(max = 254) String contactEmail,
        @Size(max = 64) String phone,
        @Size(max = 5000) String notes,
        ClientCategory category,
        @Size(max = 120) String sector,
        @Size(max = 254) String website,
        @Size(max = 254) String addressLine,
        @Size(max = 120) String city,
        @Size(max = 20) String postalCode,
        @Size(max = 80) String country,
        UUID relationshipManagerId
) {
}
