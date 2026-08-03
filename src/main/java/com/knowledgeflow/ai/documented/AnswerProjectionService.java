package com.knowledgeflow.ai.documented;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Projecção mínima, determinística e conservadora da resposta documentada por
 * {@link VisibilityLevel} (tarefa D7).
 *
 * <p>Transforma apenas a <em>apresentação</em> de uma {@link DocumentedTaxiaAnswer} numa
 * {@link AnswerProjection}: decide o que se mostra e como, conforme o nível-alvo. <strong>Não
 * decide nem recalcula</strong> {@link AnswerType}, {@link ParecerRequirement},
 * {@code supportStatus}, {@code aggregatedRiskLevel} nem {@code freshnessStatus} — recebe-os
 * já decididos pelo {@link AnswerDecisionService} (D6) e limita-se a preservá-los
 * (regras 24–26).
 *
 * <p>Princípios (regras D7):
 * <ul>
 *   <li>{@code EXTERNAL}/{@code DEMO} recebem produto profissional limpo — sem bastidores
 *       (diagnóstico interno, núcleo de fontes, notas internas, identificadores técnicos),
 *       mas <strong>com</strong> limitações, avisos e {@code parecerRequirement} (regras
 *       27–30).</li>
 *   <li>{@code DEMO} tem a mesma qualidade conceptual de {@code EXTERNAL} (regra 29).</li>
 *   <li>{@code INTERNAL} preserva diagnóstico moderado; {@code CURATION_ONLY} preserva os
 *       bastidores completos.</li>
 *   <li>Não persiste (regra 22), não cria colunas (regra 23) e não altera o objecto
 *       original.</li>
 * </ul>
 */
@Service
public class AnswerProjectionService {

    /** Corpo usado quando não há resposta apresentável — Resposta-limite não fica vazia. */
    private static final String NO_ANSWER_BODY =
            "Não existe resposta documentada disponível para apresentação.";

    /**
     * Projecta a resposta documentada para o nível-alvo pedido.
     *
     * @param answer resposta documentada já decidida (não é alterada)
     * @param targetVisibilityLevel nível-alvo; se {@code null}, usa o do {@code answer}, e
     *     se ambos forem {@code null}, {@link VisibilityLevel#INTERNAL} (default seguro)
     * @return nova projecção; nunca {@code null}
     */
    public AnswerProjection project(DocumentedTaxiaAnswer answer, VisibilityLevel targetVisibilityLevel) {
        VisibilityLevel target = resolveTarget(answer, targetVisibilityLevel);

        String visibleAnswer = buildVisibleAnswer(answer);
        List<SourceEvidence> visibleSources = projectSources(answer.sources(), target);
        List<String> visibleWarnings = safeList(answer.warnings());
        List<String> visibleLimitations = safeList(answer.limitations());

        List<String> hiddenDiagnostics = buildHiddenDiagnostics(target);
        List<String> projectionRulesApplied = buildProjectionRules(target);

        return new AnswerProjection(
                target,
                answer.answerType(),
                visibleAnswer,
                visibleSources,
                visibleWarnings,
                visibleLimitations,
                answer.parecerRequirement(),
                hiddenDiagnostics,
                projectionRulesApplied);
    }

    private VisibilityLevel resolveTarget(DocumentedTaxiaAnswer answer, VisibilityLevel target) {
        if (target != null) {
            return target;
        }
        if (answer != null && answer.visibilityLevel() != null) {
            return answer.visibilityLevel();
        }
        return VisibilityLevel.INTERNAL;
    }

    // --- corpo visível ---

    private String buildVisibleAnswer(DocumentedTaxiaAnswer answer) {
        if (answer == null) {
            return NO_ANSWER_BODY;
        }
        if (isPresent(answer.technicalAnswer())) {
            return answer.technicalAnswer();
        }
        if (isPresent(answer.shortAnswer())) {
            return answer.shortAnswer();
        }
        return NO_ANSWER_BODY;
    }

    // --- fontes ---

    private List<SourceEvidence> projectSources(List<SourceEvidence> sources, VisibilityLevel target) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<SourceEvidence> projected = new ArrayList<>(sources.size());
        for (SourceEvidence source : sources) {
            projected.add(switch (target) {
                case EXTERNAL, DEMO -> professionalSource(source);
                case INTERNAL -> internalSource(source);
                case CURATION_ONLY -> source; // bastidores completos
            });
        }
        return List.copyOf(projected);
    }

    /**
     * Vista profissional limpa: mantém só campos seguros/legíveis; oculta núcleo, diagnóstico
     * de diversidade, identificadores técnicos, notas internas, excertos e relacionados.
     */
    private SourceEvidence professionalSource(SourceEvidence s) {
        return new SourceEvidence(
                null,                 // sourceId — identificador técnico oculto
                s.title(),
                s.sourceType(),
                null,                 // authorityLevel — diagnóstico interno
                s.sourceRole(),
                s.sourceQuality(),
                null,                 // sourceCore — oculto
                null,                 // sourceDiversityGroup — oculto
                null,                 // sourceDiversity — diagnóstico cru oculto
                s.freshnessStatus(),
                s.legalReference(),
                s.url(),
                false,                // usedInAnswer — diagnóstico oculto
                s.supportsConclusion(),
                s.supportsLimitation(),
                s.supportsWarning(),
                false,                // derivativeOrReplicated — diagnóstico cru oculto
                List.of(),            // relatedSources — oculto
                null,                 // excerpt — oculto
                List.of());           // notesInternal — oculto
    }

    /**
     * Vista interna: mantém o diagnóstico moderado (núcleo, diversidade, papel, flags),
     * ocultando apenas as notas internas mais sensíveis (regra 9). O {@code CURATION_ONLY}
     * é que preserva tudo.
     */
    private SourceEvidence internalSource(SourceEvidence s) {
        if (s.notesInternal() == null || s.notesInternal().isEmpty()) {
            return s;
        }
        return new SourceEvidence(
                s.sourceId(),
                s.title(),
                s.sourceType(),
                s.authorityLevel(),
                s.sourceRole(),
                s.sourceQuality(),
                s.sourceCore(),
                s.sourceDiversityGroup(),
                s.sourceDiversity(),
                s.freshnessStatus(),
                s.legalReference(),
                s.url(),
                s.usedInAnswer(),
                s.supportsConclusion(),
                s.supportsLimitation(),
                s.supportsWarning(),
                s.derivativeOrReplicated(),
                s.relatedSources(),
                s.excerpt(),
                List.of());           // notesInternal — reservado a CURATION_ONLY
    }

    // --- diagnóstico da projecção ---

    private List<String> buildHiddenDiagnostics(VisibilityLevel target) {
        return switch (target) {
            case EXTERNAL, DEMO -> List.of(
                    "internalDiagnostics",
                    "sourceCore",
                    "sourceDiversityGroup",
                    "sourceDiversity",
                    "notesInternal",
                    "technicalIdentifiers");
            case INTERNAL -> List.of("notesInternal");
            case CURATION_ONLY -> List.of();
        };
    }

    private List<String> buildProjectionRules(VisibilityLevel target) {
        List<String> rules = new ArrayList<>();
        rules.add("visibility:" + target.name());
        switch (target) {
            case EXTERNAL, DEMO -> {
                rules.add("hide:internalDiagnostics");
                rules.add("hide:sourceCore");
                rules.add("hide:sourceDiversityGroup");
                rules.add("hide:notesInternal");
                rules.add("hide:technicalIdentifiers");
                if (target == VisibilityLevel.DEMO) {
                    rules.add("demo:same-quality-as-external");
                }
            }
            case INTERNAL -> {
                rules.add("preserve:diagnostics-moderate");
                rules.add("hide:notesInternal");
            }
            case CURATION_ONLY -> rules.add("preserve:all-backstage");
        }
        rules.add("preserve:answerType");
        rules.add("preserve:parecerRequirement");
        rules.add("preserve:limitations");
        rules.add("preserve:warnings");
        return List.copyOf(rules);
    }

    // --- helpers ---

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> safeList(List<String> list) {
        return list != null ? List.copyOf(list) : List.of();
    }
}
