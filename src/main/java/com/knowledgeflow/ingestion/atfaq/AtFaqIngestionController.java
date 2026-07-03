package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.ingestion.atfaq.AtFaqImportService.AtFaqRunResult;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operations of the AT FAQ ingestion pilot.
 * The organization is always derived from the authenticated user —
 * it is never accepted in the payload.
 */
@RestController
@RequestMapping("/api/v1/admin/ingestion/at-faq")
@PreAuthorize("hasRole('ADMIN')")
public class AtFaqIngestionController {

    private final AtFaqImportService importService;
    private final AtFaqIngestionRunRepository runRepository;
    private final AuthenticatedUserContext authContext;

    public AtFaqIngestionController(
            AtFaqImportService importService,
            AtFaqIngestionRunRepository runRepository,
            AuthenticatedUserContext authContext) {
        this.importService = importService;
        this.runRepository = runRepository;
        this.authContext = authContext;
    }

    /** Optional per-run limits — they can only tighten the configured maximums. */
    public record AtFaqRunRequest(Integer maxPages, Integer maxItems) {
    }

    public record AtFaqRunResponse(
            UUID runId,
            String mode,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String reportJson,
            String errorMessage) {
    }

    @PostMapping("/discover")
    public AtFaqRunResult discover() {
        AuthenticatedUser user = authContext.getRequiredUser();
        return importService.discover(user.organizationId(), user.userId());
    }

    @PostMapping("/dry-run")
    public AtFaqRunResult dryRun(@RequestBody(required = false) AtFaqRunRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return importService.dryRun(user.organizationId(), user.userId(),
                request == null ? null : request.maxPages(),
                request == null ? null : request.maxItems());
    }

    @PostMapping("/import")
    public AtFaqRunResult importRun(@RequestBody(required = false) AtFaqRunRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        return importService.importRun(user.organizationId(), user.userId(),
                request == null ? null : request.maxPages(),
                request == null ? null : request.maxItems());
    }

    @GetMapping("/runs/{id}")
    public AtFaqRunResponse getRun(@PathVariable UUID id) {
        AuthenticatedUser user = authContext.getRequiredUser();
        AtFaqIngestionRun run = runRepository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Ingestion run not found: " + id));
        return new AtFaqRunResponse(
                run.getId(),
                run.getMode().name(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getReportJson(),
                run.getErrorMessage());
    }
}
