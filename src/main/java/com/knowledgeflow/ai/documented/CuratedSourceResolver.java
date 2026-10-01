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
 * Fontes repetidas entre Q&amp;A ficam só na primeira ocorrência: por URL quando existe; senão por
 * tipo, referência legal e título. Sem limite de quantidade.
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

    public CuratedSourceResolver(KnowledgeSourceReferenceRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
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
        Set<String> seenKeys = new HashSet<>();
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
            rows.stream()
                    .sorted(WITHIN_QA_ORDER)
                    .filter(row -> seenKeys.add(dedupKey(row)))
                    .forEach(row -> resolved.add(ResolvedAnswerSource.curated(source, toCurated(row))));
        }
        return List.copyOf(resolved);
    }

    private static CuratedSource toCurated(KnowledgeSourceReferenceRow row) {
        return new CuratedSource(
                row.id(),
                row.sourceType() != null ? row.sourceType().name() : null,
                row.title(),
                row.legalReference(),
                row.url());
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
