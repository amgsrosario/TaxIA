package com.knowledgeflow.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With knowledgeflow.auth.bootstrap.enabled=false (the production default),
 * the bootstrap endpoint answers 404 — indistinguishable from a route that
 * does not exist — even with the correct secret.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "knowledgeflow.auth.bootstrap.enabled=false")
@AutoConfigureMockMvc
class BootstrapDisabledHttpTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Bootstrap desactivado → 404 mesmo com secret correcto")
    void disabledBootstrapAnswersNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bootstrap-Secret", "test-bootstrap-secret")
                        .content("""
                                {
                                  "organizationName": "Org Disabled",
                                  "taxIdentifier": null,
                                  "adminEmail": "disabled@hardening.test",
                                  "adminFullName": "Disabled Admin",
                                  "adminPassword": "password-123"
                                }
                                """))
                .andExpect(status().isNotFound());
    }
}
