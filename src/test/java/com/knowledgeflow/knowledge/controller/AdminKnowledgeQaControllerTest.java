package com.knowledgeflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.common.error.GlobalExceptionHandler;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.KnowledgeQaDetailResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaResponse;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.service.KnowledgeBenchmarkDraftService;
import com.knowledgeflow.knowledge.service.KnowledgeQaSimilarityService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminKnowledgeQaControllerTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QA_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock private KnowledgeQuestionAnswerImportService importService;
    @Mock private KnowledgeQuestionAnswerCurationService curationService;
    @Mock private KnowledgeQuestionAnswerPublicationService publicationService;
    @Mock private KnowledgeQaSimilarityService similarityService;
    @Mock private KnowledgeBenchmarkDraftService benchmarkDraftService;
    @Mock private AuthenticatedUserContext authContext;

    @InjectMocks private AdminKnowledgeQaController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();

        when(authContext.getRequiredUser()).thenReturn(
                new AuthenticatedUser(USER_ID, ORG_ID, "admin@taxia.pt", List.of("ROLE_ADMIN")));
    }

    // 24. GET /api/v1/admin/knowledge/qa → 200 com página
    @Test
    void listEndpoint_returnsPageOfQa() throws Exception {
        KnowledgeQaResponse item = new KnowledgeQaResponse(
                QA_ID, null, null, "Qual o IVA?", KnowledgeTopic.IVA, null,
                KnowledgeRiskLevel.MEDIUM, KnowledgeCurationStatus.IMPORTED,
                false, false, null, null, false,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(curationService.list(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/knowledge/qa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(QA_ID.toString()));
    }

    // 25. GET /api/v1/admin/knowledge/qa/{id} → 200 com detalhe
    @Test
    void detailEndpoint_returnsQaDetail() throws Exception {
        KnowledgeQaDetailResponse detail = new KnowledgeQaDetailResponse(
                QA_ID, null, null, "Qual o IVA?", "Resposta.",
                null, null, null,
                KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.MEDIUM, false,
                KnowledgeCurationStatus.IMPORTED, false,
                null, null, null, null, null,
                false, null, null, null, 0,
                OffsetDateTime.now(), OffsetDateTime.now(), List.of());
        when(curationService.getDetail(ORG_ID, QA_ID)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", QA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(QA_ID.toString()));
    }

    // 26. POST /api/v1/admin/knowledge/qa/import (CSV) → 200 com relatório
    @Test
    void importEndpoint_csvFile_returns200WithReport() throws Exception {
        ImportReport report = new ImportReport(2, 2, 0, 0, 0, 0, 0, false, List.of());
        when(importService.importCsv(any(), any(), anyString(), any(InputStream.class), anyBoolean(), anyInt()))
                .thenReturn(report);

        MockMultipartFile file = new MockMultipartFile("file", "test.csv",
                "text/csv", "question,answer\nQ?,A.".getBytes());
        MockMultipartFile requestPart = new MockMultipartFile("request", "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"sourceSystem":"test","format":"CSV","dryRun":false,"limit":10}
                """.getBytes());

        mockMvc.perform(multipart("/api/v1/admin/knowledge/qa/import")
                        .file(file)
                        .file(requestPart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2));
    }

    // 27. POST /api/v1/admin/knowledge/qa/{id}/validate → 204
    @Test
    void validateEndpoint_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/validate", QA_ID)
                        .param("reviewerName", "revisor@taxia.pt"))
                .andExpect(status().isNoContent());
    }

    // 28. POST /api/v1/admin/knowledge/qa/{id}/publish → 204
    @Test
    void publishEndpoint_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/publish", QA_ID)
                        .param("publisherName", "pub@taxia.pt"))
                .andExpect(status().isNoContent());
    }

    // 29. GET /api/v1/admin/knowledge/qa/similar → lista de similares
    @Test
    void similarEndpoint_returnsResults() throws Exception {
        when(similarityService.findSimilar(any(), anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/knowledge/qa/similar")
                        .param("question", "Qual o IVA?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // 30. POST /api/v1/admin/knowledge/qa/{id}/canonical → 204
    @Test
    void canonicalEndpoint_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/canonical", QA_ID)
                        .param("canonical", "true"))
                .andExpect(status().isNoContent());
    }
}
