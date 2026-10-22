package com.knowledgeflow.research.controller;

import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.CriticalMemoryHistory;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.CriticalStatus;
import com.knowledgeflow.research.service.CriticalMemoryService;
import com.knowledgeflow.research.service.InvestigationService;
import com.knowledgeflow.research.service.InvestigationTraceService;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Investigação a partir de pergunta inédita (KF-M01, ADR-010). Só ADMIN; só existe com
 * {@code knowledgeflow.research.enabled=true}. Separado do {@code /ask}: a integração no fluxo de
 * resposta ao utilizador é decisão de produto ainda não tomada.
 * <p>
 * A organização vem sempre do utilizador autenticado, nunca do payload.
 */
@RestController
@RequestMapping("/api/v1/admin/research")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(prefix = "knowledgeflow.research", name = "enabled", havingValue = "true")
public class AdminResearchController {

    private final InvestigationService investigationService;
    private final InvestigationTraceService traceService;
    private final CriticalMemoryService criticalMemoryService;
    private final AuthenticatedUserContext authContext;

    public AdminResearchController(InvestigationService investigationService, InvestigationTraceService traceService,
            CriticalMemoryService criticalMemoryService, AuthenticatedUserContext authContext) {
        this.investigationService = investigationService;
        this.traceService = traceService;
        this.criticalMemoryService = criticalMemoryService;
        this.authContext = authContext;
    }

    public record InvestigationRequest(@NotBlank @Size(max = 4000) String question) {}

    public record StatusChangeRequest(@NotNull CriticalStatus status, @NotBlank @Size(max = 2000) String reason) {}

    @PostMapping("/investigations")
    public ResearchTraceView investigate(@Valid @RequestBody InvestigationRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        ResearchInvestigation investigation = investigationService.investigate(
                user.organizationId(), user.userId(), request.question());
        return ResearchTraceView.from(traceService.trace(user.organizationId(), investigation.getId()));
    }

    @GetMapping("/investigations/{id}")
    public ResearchTraceView trace(@PathVariable UUID id) {
        Trace trace = traceService.trace(authContext.getRequiredUser().organizationId(), id);
        return ResearchTraceView.from(trace);
    }

    @GetMapping("/critical-memory")
    public List<ResearchTraceView.CriticalMemoryView> criticalMemory(@RequestParam List<String> referenceKey) {
        return criticalMemoryService.findByReferenceKeys(authContext.getRequiredUser().organizationId(), referenceKey)
                .stream().map(ResearchTraceView.CriticalMemoryView::from).toList();
    }

    @GetMapping("/critical-memory/{id}/history")
    public List<CriticalMemoryHistory> history(@PathVariable UUID id) {
        return criticalMemoryService.history(authContext.getRequiredUser().organizationId(), id);
    }

    /** Reavaliação humana do estado de uma entrada; não a torna fundamento positivo. */
    @PatchMapping("/critical-memory/{id}/status")
    public ResearchTraceView.CriticalMemoryView changeStatus(@PathVariable UUID id,
            @Valid @RequestBody StatusChangeRequest request) {
        AuthenticatedUser user = authContext.getRequiredUser();
        CriticalMemoryEntry entry = criticalMemoryService.changeStatus(user.organizationId(), user.userId(),
                user.email(), id, request.status(), request.reason());
        return ResearchTraceView.CriticalMemoryView.from(entry);
    }
}
