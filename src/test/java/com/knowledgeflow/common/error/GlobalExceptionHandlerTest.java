package com.knowledgeflow.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessExceptionUsesStandardErrorContract() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource was not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/test-errors/not-found"));
    }

    @Test
    void invalidJsonBodyMapsTo400WithoutInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/test-errors/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value(
                        "Request body is not valid JSON or is not encoded as UTF-8"))
                .andExpect(jsonPath("$.path").value("/api/v1/test-errors/echo"));
    }

    @RestController
    @RequestMapping("/api/v1/test-errors")
    public static class TestController {

        @GetMapping("/not-found")
        public ResponseEntity<Void> notFound() {
            throw new ResourceNotFoundException("Resource was not found");
        }

        @PostMapping("/echo")
        public ResponseEntity<String> echo(@RequestBody EchoRequest body) {
            return ResponseEntity.ok(body.message());
        }

        record EchoRequest(String message) {}
    }
}
