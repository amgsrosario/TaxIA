package com.knowledgeflow.knowledge.controller;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.ApplicabilityExclusionRequest;
import com.knowledgeflow.knowledge.dto.BenchmarkDraftCase;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.KnowledgeQaApplicabilityResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest;
import com.knowledgeflow.knowledge.dto.KnowledgeQaDetailResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaImportRequest;
import com.knowledgeflow.knowledge.dto.KnowledgeQaResponse;
import com.knowledgeflow.knowledge.dto.SimilarQaResult;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.dto.SourceReferenceResponse;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.service.KnowledgeBenchmarkDraftService;
import com.knowledgeflow.knowledge.service.KnowledgeQaApplicabilityService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final KnowledgeQaApplicabilityService applicabilityService;
    private final AuthenticatedUserContext authContext;

    public AdminKnowledgeQaController(
            KnowledgeQuestionAnswerImportService importService,
            KnowledgeQuestionAnswerCurationService curationService,
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQaSimilarityService similarityService,
            KnowledgeBenchmarkDraftService benchmarkDraftService,
            KnowledgeQaApplicabilityService applicabilityService,
            AuthenticatedUserContext authContext) {
        this.importService = importService;
        this.curationService = curationService;
        this.publicationService = publicationService;
        this.similarityService = similarityService;
        this.benchmarkDraftService = benchmarkDraftService;
        this.applicabilityService = applicabilityService;
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

    /**
     * Updates curated fields. {@code expectedVersion} is mandatory (lost-update protection);
     * material changes to a published entry answer 409 (create a new version — ADR-005).
     */
    @PatchMapping("/{id}/curation")
    public KnowledgeQaDetailResponse updateCuration(
            @PathVariable UUID id,
            @RequestBody KnowledgeQaCurationRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "expectedVersion is required (the version of the entry being edited)");
        }
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
            @RequestParam String reviewerName,
            @RequestParam(required = false) Integer expectedVersion) {
        // ADR-005: valida-se a versão que o revisor leu
        if (expectedVersion == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "expectedVersion is required (the version of the entry being validated)");
        }
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.validate(user.organizationId(), user.userId(), reviewerName, id, expectedVersion);
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

    /** Removes a wrong source. Never allowed to leave a validated/published entry sourceless. */
    @DeleteMapping("/{id}/sources/{sourceId}")
    public ResponseEntity<Void> removeSource(
            @PathVariable UUID id,
            @PathVariable UUID sourceId) {
        AuthenticatedUser user = authContext.getRequiredUser();
        curationService.removeSource(user.organizationId(), user.userId(), id, sourceId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Applicability exclusions (M4-SCOPE-V2, ADR-004)
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/applicability")
    public KnowledgeQaApplicabilityResponse applicability(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.get(user.organizationId(), id);
    }

    /** Adds an exclusion: narrows the scope, effective immediately (also on published entries). */
    @PostMapping("/{id}/applicability/exclusions")
    public KnowledgeQaApplicabilityResponse addApplicabilityExclusion(
            @PathVariable UUID id,
            @RequestBody ApplicabilityExclusionRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.addExclusion(user.organizationId(), user.userId(), user.email(), id, request);
    }

    /**
     * Removes an exclusion from an unpublished entry, or only requests the removal on a published
     * entry (the exclusion stays effective until approve-removal).
     */
    @DeleteMapping("/{id}/applicability/exclusions/{marker}")
    public KnowledgeQaApplicabilityResponse removeApplicabilityExclusion(
            @PathVariable UUID id,
            @PathVariable String marker) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.removeExclusion(user.organizationId(), user.userId(), user.email(), id, marker);
    }

    /** Human validation of a pending removal: only now does the exclusion stop being effective. */
    @PostMapping("/{id}/applicability/exclusions/{marker}/approve-removal")
    public KnowledgeQaApplicabilityResponse approveApplicabilityRemoval(
            @PathVariable UUID id,
            @PathVariable String marker,
            @RequestParam String reviewerName) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.approveRemoval(user.organizationId(), user.userId(), reviewerName, id, marker);
    }

    @PostMapping("/{id}/applicability/exclusions/{marker}/cancel-removal")
    public KnowledgeQaApplicabilityResponse cancelApplicabilityRemoval(
            @PathVariable UUID id,
            @PathVariable String marker) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.cancelRemoval(user.organizationId(), user.userId(), user.email(), id, marker);
    }

    @PostMapping("/{id}/applicability/reviewed")
    public KnowledgeQaApplicabilityResponse markApplicabilityReviewed(
            @PathVariable UUID id,
            @RequestParam String reviewerName) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return applicabilityService.markReviewed(user.organizationId(), user.userId(), reviewerName, id);
    }

    // -------------------------------------------------------------------------
    // Publication
    // -------------------------------------------------------------------------

    /**
     * Creates a new, unpublished version of a published entry (copy of content, sources and
     * exclusions). The published version keeps answering until publish-replacing (ADR-005).
     */
    @PostMapping("/{id}/versions")
    public KnowledgeQaDetailResponse createVersion(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        KnowledgeQuestionAnswer created = publicationService.createNewVersion(user.organizationId(), user.userId(), id);
        return curationService.getDetail(user.organizationId(), created.getId());
    }

    /**
     * Publishes the VALIDATED new version {@code id} and unpublishes the published version it
     * replaces, atomically (ADR-005).
     */
    @PostMapping("/{id}/publish-replacing/{previousId}")
    public ResponseEntity<Void> publishReplacing(
            @PathVariable UUID id,
            @PathVariable UUID previousId,
            @RequestParam String publisherName) {
        AuthenticatedUser user = authContext.getRequiredUser();
        publicationService.publishReplacing(user.organizationId(), user.userId(), publisherName, id, previousId);
        return ResponseEntity.noContent().build();
    }

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
