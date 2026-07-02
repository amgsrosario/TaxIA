package com.knowledgeflow.knowledge.controller;

import com.knowledgeflow.knowledge.dto.BenchmarkDraftCase;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest;
import com.knowledgeflow.knowledge.dto.KnowledgeQaDetailResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaImportRequest;
import com.knowledgeflow.knowledge.dto.KnowledgeQaResponse;
import com.knowledgeflow.knowledge.dto.SimilarQaResult;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.dto.SourceReferenceResponse;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.service.KnowledgeBenchmarkDraftService;
import com.knowledgeflow.knowledge.service.KnowledgeQaSimilarityService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/knowledge/qa")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKnowledgeQaController {

    private final KnowledgeQuestionAnswerImportService importService;
    private final KnowledgeQuestionAnswerCurationService curationService;
    private final KnowledgeQuestionAnswerPublicationService publicationService;
    private final KnowledgeQaSimilarityService similarityService;
    private final KnowledgeBenchmarkDraftService benchmarkDraftService;
    private final AuthenticatedUserContext authContext;

    public AdminKnowledgeQaController(
            KnowledgeQuestionAnswerImportService importService,
            KnowledgeQuestionAnswerCurationService curationService,
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQaSimilarityService similarityService,
            KnowledgeBenchmarkDraftService benchmarkDraftService,
            AuthenticatedUserContext authContext) {
        this.importService = importService;
        this.curationService = curationService;
        this.publicationService = publicationService;
        this.similarityService = similarityService;
        this.benchmarkDraftService = benchmarkDraftService;
        this.authContext = authContext;
    }

    // -------------------------------------------------------------------------
    // List & detail
    // -------------------------------------------------------------------------

    @GetMapping
    public Page<KnowledgeQaResponse> list(
            @RequestParam(required = false) KnowledgeCurationStatus status,
            @RequestParam(required = false) KnowledgeTopic topic,
            @PageableDefault(size = 20) Pageable pageable) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return curationService.list(user.organizationId(), status, topic, pageable);
    }

    @GetMapping("/{id}")
    public KnowledgeQaDetailResponse detail(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return curationService.getDetail(user.organizationId(), id);
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport importFile(
            @RequestPart("file") MultipartFile file,
            @RequestPart("request") @Valid KnowledgeQaImportRequest request)
            throws IOException {

        AuthenticatedUser user = authContext.getRequiredUser();
        UUID orgId = user.organizationId();
        UUID userId = user.userId();

        return switch (request.format()) {
            case CSV -> importService.importCsv(orgId, userId, request.sourceSystem(),
                    file.getInputStream(), request.dryRun(), request.limit());
            case JSON -> importService.importJson(orgId, userId, request.sourceSystem(),
                    file.getInputStream(), request.dryRun(), request.limit());
        };
    }

    // -------------------------------------------------------------------------
    // Curation
    // -------------------------------------------------------------------------

    @PatchMapping("/{id}/curation")
    public KnowledgeQaDetailResponse updateCuration(
            @PathVariable UUID id,
            @RequestBody KnowledgeQaCurationRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return curationService.updateCuration(user.organizationId(), user.userId(), id, request);
    }

    @PostMapping("/{id}/pending-review")
    public ResponseEntity<Void> markPendingReview(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.markPendingReview(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<Void> validate(
            @PathVariable UUID id,
            @RequestParam String reviewerName) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.validate(user.organizationId(), user.userId(), reviewerName, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable UUID id,
            @RequestParam(required = false) String reviewerName,
            @RequestParam(required = false) String reason) {
        AuthenticatedUser user = authContext.getRequiredUser();
        String reviewer = reviewerName != null ? reviewerName : user.email();
        curationService.reject(user.organizationId(), user.userId(), reviewer, id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/outdated")
    public ResponseEntity<Void> markOutdated(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.markOutdated(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.archive(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/canonical")
    public ResponseEntity<Void> setCanonical(
            @PathVariable UUID id,
            @RequestParam boolean canonical) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.setCanonical(user.organizationId(), user.userId(), id, canonical);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/sources")
    public SourceReferenceResponse addSource(
            @PathVariable UUID id,
            @RequestBody @Valid SourceReferenceRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return curationService.addSource(user.organizationId(), user.userId(), id, request);
    }

    @GetMapping("/{id}/sources")
    public List<SourceReferenceResponse> listSources(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return curationService.listSources(user.organizationId(), id);
    }

    // -------------------------------------------------------------------------
    // Publication
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(
            @PathVariable UUID id,
            @RequestParam String publisherName) {
        AuthenticatedUser user = authContext.getRequiredUser();
        publicationService.publish(user.organizationId(), user.userId(), publisherName, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        publicationService.unpublish(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Idempotent embedding reprocessing for an already-published entry. */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Void> reindex(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        publicationService.reindex(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Similarity search
    // -------------------------------------------------------------------------

    /** Find similar Q&A entries for a free-form question. */
    @GetMapping("/similar")
    public List<SimilarQaResult> findSimilar(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int topK) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return similarityService.findSimilar(user.organizationId(), question, topK);
    }

    /** Find similar Q&A entries based on the question of an existing entry. */
    @GetMapping("/{id}/similar")
    public List<SimilarQaResult> findSimilarById(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "10") int topK) {
        AuthenticatedUser user = authContext.getRequiredUser();
        KnowledgeQaDetailResponse detail = curationService.getDetail(user.organizationId(), id);
        return similarityService.findSimilar(user.organizationId(), detail.originalQuestion(), topK);
    }

    // -------------------------------------------------------------------------
    // Benchmark draft generation
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/benchmark-draft")
    public BenchmarkDraftCase generateBenchmarkDraft(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return benchmarkDraftService.generateDraft(user.organizationId(), id);
    }

    @PostMapping("/benchmark-drafts")
    public List<BenchmarkDraftCase> generateBenchmarkDrafts(@RequestBody List<UUID> qaIds) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return benchmarkDraftService.generateDrafts(user.organizationId(), qaIds);
    }
}
