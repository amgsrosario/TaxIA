package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.GroundingFoundation;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.repository.KnowledgeQaGovernanceRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lê o risco e a validação humana persistidos das Q&amp;A usadas como fundamento (M4B, ADR-007) e
 * agrega-os em {@link FoundationGovernance}. Só leitura; uma query por pedido.
 *
 * <p>Fail-closed: um fundamento sem Q&amp;A ({@code DOCUMENT}), uma Q&amp;A inexistente, um risco
 * ausente ou desconhecido, ou uma falha de leitura dão risco desconhecido e validação humana
 * {@code true} para esse fundamento — nunca um valor por omissão silencioso. Uma Q&amp;A de outra
 * organização conta como inexistente.
 *
 * <p>Sem {@code @Transactional} de propósito: uma falha da query marcaria a transacção envolvente
 * como rollback-only e escaparia no commit ({@code UnexpectedRollbackException}), anulando o
 * fail-closed. A leitura única corre sem transacção envolvente.
 */
@Component
public class FoundationGovernanceResolver {

    private static final Logger log = LoggerFactory.getLogger(FoundationGovernanceResolver.class);

    private final KnowledgeQuestionAnswerRepository repository;

    public FoundationGovernanceResolver(KnowledgeQuestionAnswerRepository repository) {
        this.repository = repository;
    }

    public FoundationGovernance resolve(UUID organizationId, List<GroundingFoundation> foundations) {
        if (foundations == null || foundations.isEmpty()) {
            return FoundationGovernance.of(List.of());
        }
        Set<UUID> ids = foundations.stream().map(GroundingFoundation::sourceQaId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, KnowledgeQaGovernanceRow> rows;
        String failure = null;
        try {
            rows = ids.isEmpty() || organizationId == null ? Map.of()
                    : repository.findGovernanceRowsByIdIn(organizationId, ids).stream()
                    .collect(Collectors.toMap(r -> UUID.fromString(r.getId().trim()), Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("Foundation governance: could not read risk metadata for {} Q&A ({}); treated as unknown",
                    ids.size(), e.getClass().getSimpleName());
            rows = Map.of();
            failure = "falha de leitura dos metadados de risco";
        }

        List<FoundationGovernance.FoundationRisk> risks = new ArrayList<>(foundations.size());
        Set<UUID> seen = new LinkedHashSet<>();
        for (GroundingFoundation f : foundations) {
            UUID id = f.sourceQaId();
            if (id != null && !seen.add(id)) {
                continue;
            }
            if (id == null) {
                risks.add(unknown(f, "fundamento sem Q&A governada (DOCUMENT)"));
                continue;
            }
            KnowledgeQaGovernanceRow row = rows.get(id);
            if (row == null) {
                risks.add(unknown(f, failure != null ? failure
                        : organizationId == null ? "organização desconhecida" : "Q&A inexistente"));
                continue;
            }
            KnowledgeRiskLevel level = parse(row.getRiskLevel());
            boolean humanValidation = !Boolean.FALSE.equals(row.getRequiresHumanValidation());
            if (level == null) {
                risks.add(new FoundationGovernance.FoundationRisk(id, f.title(), null, true,
                        row.getRiskLevel() == null ? "riskLevel ausente" : "riskLevel desconhecido"));
            } else {
                risks.add(new FoundationGovernance.FoundationRisk(id, f.title(), level, humanValidation, null));
            }
        }
        return FoundationGovernance.of(risks);
    }

    private static FoundationGovernance.FoundationRisk unknown(GroundingFoundation f, String reason) {
        return new FoundationGovernance.FoundationRisk(f.sourceQaId(), f.title(), null, true, reason);
    }

    private static KnowledgeRiskLevel parse(String raw) {
        if (raw == null) {
            return null;
        }
        return Arrays.stream(KnowledgeRiskLevel.values()).filter(l -> l.name().equals(raw.trim()))
                .findFirst().orElse(null);
    }
}
