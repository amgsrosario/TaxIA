package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.documented.ResolvedAnswerSource.CuratedSource;
import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolve as fontes do grounding nas fontes documentais curadas das Q&amp;A recuperadas (M3).
 *
 * <p>Só leitura. A ligação usa exclusivamente o {@code sourceQaId} transportado pelo
 * {@link AnswerSource} — nunca títulos, perguntas, URLs ou referências. Todas as fontes curadas
 * são lidas numa única query. A ordem segue a ordem das fontes do grounding (a do RAG) e, dentro
 * de cada Q&amp;A, {@code createdAt}, título normalizado e id — nunca a ordem devolvida pela base.
 * Fontes repetidas entre Q&amp;A ficam uma só vez (por URL quando existe; senão por tipo, referência
 * legal e título), na posição da primeira ocorrência. Desde o ADR-008 cada cópia tem a sua própria
 * verificação e {@code validTo}: na colisão fica a cópia com o PIOR estado de actualidade calculado,
 * para que a deduplicação nunca esconda uma fonte desactualizada ou não verificada. Sem limite de
 * quantidade.
 *
 * <p>Uma fonte do grounding sem {@code sourceQaId} (casos {@code DOCUMENT}) ou cuja Q&amp;A não
 * tem fontes curadas mantém-se tal como está (fallback), para não apagar evidência. Sem fontes no
 * grounding (Resposta-limite, resposta rejeitada) não há nada a resolver nem query.
 */
@Component
public class CuratedSourceResolver {

    private static final Comparator<KnowledgeSourceReferenceRow> WITHIN_QA_ORDER = Comparator
            .comparing(KnowledgeSourceReferenceRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(row -> normalize(row.title()))
            .thenComparing(KnowledgeSourceReferenceRow::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private final KnowledgeSourceReferenceRepository sourceRepository;

    private final SourceFreshnessPolicy freshnessPolicy;

    /** Testes/legado: política com o relógio do sistema. */
    public CuratedSourceResolver(KnowledgeSourceReferenceRepository sourceRepository) {
        this(sourceRepository, new SourceFreshnessPolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CuratedSourceResolver(KnowledgeSourceReferenceRepository sourceRepository,
            SourceFreshnessPolicy freshnessPolicy) {
        this.sourceRepository = sourceRepository;
        this.freshnessPolicy = freshnessPolicy;
    }

    public List<ResolvedAnswerSource> resolve(List<AnswerSource> groundedSources) {
        if (groundedSources == null || groundedSources.isEmpty()) {
            return List.of();
        }

        Set<UUID> qaIds = groundedSources.stream()
                .map(AnswerSource::sourceQaId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<KnowledgeSourceReferenceRow>> rowsByQa = qaIds.isEmpty()
                ? Map.of()
                : sourceRepository.findRowsByQuestionAnswerIdIn(qaIds).stream()
                        .collect(Collectors.groupingBy(KnowledgeSourceReferenceRow::questionAnswerId));

        List<ResolvedAnswerSource> resolved = new ArrayList<>();
        Set<UUID> expandedQas = new HashSet<>();
        Map<String, Integer> positionByKey = new java.util.HashMap<>();
        for (AnswerSource source : groundedSources) {
            List<KnowledgeSourceReferenceRow> rows = source.sourceQaId() == null
                    ? List.of()
                    : rowsByQa.getOrDefault(source.sourceQaId(), List.of());
            if (rows.isEmpty()) {
                resolved.add(ResolvedAnswerSource.fallback(source));
                continue;
            }
            if (!expandedQas.add(source.sourceQaId())) {
                continue;
            }
            for (KnowledgeSourceReferenceRow row : rows.stream().sorted(WITHIN_QA_ORDER).toList()) {
                ResolvedAnswerSource candidate = ResolvedAnswerSource.curated(source, toCurated(row));
                Integer position = positionByKey.putIfAbsent(dedupKey(row), resolved.size());
                if (position == null) {
                    resolved.add(candidate);
                } else if (severity(candidate) > severity(resolved.get(position))) {
                    resolved.set(position, candidate); // a cópia pior prevalece (ADR-008)
                }
            }
        }
        return List.copyOf(resolved);
    }

    private int severity(ResolvedAnswerSource resolved) {
        return SourceFreshnessPolicy.severity(freshnessPolicy.assess(resolved.curated().freshnessFacts()).status());
    }

    private static CuratedSource toCurated(KnowledgeSourceReferenceRow row) {
        return new CuratedSource(
                row.id(),
                row.sourceType() != null ? row.sourceType().name() : null,
                row.title(),
                row.legalReference(),
                row.url(),
                new SourceFreshnessPolicy.Facts(row.sourceType(), row.title(), row.legalReference(), row.validTo(),
                        row.verifiedAt(), row.verifiedBy() != null, row.consolidatedVersionConfirmed(),
                        row.stableMatter(), row.stableMatterReason(), row.stableMatterMarkedBy() != null,
                        row.validFrom()));
    }

    private static String dedupKey(KnowledgeSourceReferenceRow row) {
        if (row.url() != null && !row.url().isBlank()) {
            return "url:" + row.url().strip();
        }
        return "ref:" + row.sourceType() + "|" + normalize(row.legalReference()) + "|" + normalize(row.title());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
