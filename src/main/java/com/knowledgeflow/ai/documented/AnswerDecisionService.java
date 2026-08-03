package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Decisão mínima, determinística e conservadora da resposta documentada (tarefa D6).
 *
 * <p>Concentra a decisão que antes estava dispersa no {@link DocumentedTaxiaAnswerMapper}:
 * {@link AnswerType}, {@link ParecerRequirement}, {@code confidenceSummary},
 * {@code limitations}, {@code warnings}, {@code nextSteps} e {@code sourceSummary}.
 * Combina {@link AnswerSupportStatus} e {@code requiresHumanValidation} com os sinais já
 * avaliados em cada {@link SourceEvidence} (qualidade, autoridade, actualidade,
 * diversidade, papel).
 *
 * <p>Princípios (regras D6):
 * <ul>
 *   <li><strong>Sem scoring numérico nem thresholds</strong> — só sinais qualitativos.</li>
 *   <li><strong>Conservadorismo</strong> — na dúvida, prudência (limitação/aviso/parecer),
 *       nunca silêncio: Resposta-limite não é não-resposta (regra 28) e Pedido de parecer
 *       não é erro (regra 29).</li>
 *   <li><strong>Volume não é robustez</strong> — não se conta fontes em bruto; não se finge
 *       diversidade se {@code sourceDiversity} não for {@code MATERIAL_DIVERSITY}.</li>
 *   <li>Não decide risco agregado nem projecção por visibilidade (regras 21, 25, 27).</li>
 * </ul>
 */
@Service
public class AnswerDecisionService {

    /**
     * Decide a forma/prudência da resposta documentada.
     *
     * @param supportStatus estado de suporte do grounding (pode ser {@code null})
     * @param requiresHumanValidation sinalização de validação humana do grounding
     * @param sources fontes já avaliadas (D5); nunca {@code null} — usar {@link List#of()}
     * @param baseLimitations limitações já declaradas a montante (ex.: modelo); podem ser
     *     {@code null}
     * @param validationMessage mensagem de validação humana, se existir
     * @return decisão consolidada
     */
    public AnswerDecision decide(
            AnswerSupportStatus supportStatus,
            boolean requiresHumanValidation,
            List<SourceEvidence> sources,
            List<String> baseLimitations,
            String validationMessage) {

        List<SourceEvidence> safeSources = sources != null ? sources : List.of();

        boolean hasSources = hasSources(safeSources);
        boolean hasStrongOrAdequateSource = hasStrongOrAdequateSource(safeSources);
        boolean hasOnlyWeakSources = hasOnlyWeakSources(safeSources);
        boolean hasOutdatedSources = hasOutdatedSources(safeSources);
        boolean hasUncertainFreshness = hasUncertainFreshness(safeSources);
        boolean hasOfficialOrLegalAuthority = hasOfficialOrLegalAuthority(safeSources);
        boolean hasOnlyDerivative = hasOnlyDerivativeReplicatedSources(safeSources);
        boolean lacksMaterialDiversity = safeSources.size() >= 2 && !hasMaterialDiversity(safeSources);

        // Sinais positivos de fraqueza documental. A ausência de fontes não é usada para
        // rebaixar um SUPPORTED do grounding (evita segundo-adivinhar a decisão a montante).
        boolean limitationSignals = hasOnlyWeakSources || hasOutdatedSources || hasOnlyDerivative
                || lacksMaterialDiversity
                || (hasSources && !hasStrongOrAdequateSource);

        AnswerType answerType = decideAnswerType(supportStatus, limitationSignals);
        ParecerRequirement parecerRequirement = decideParecerRequirement(
                supportStatus, requiresHumanValidation, hasSources, hasOutdatedSources,
                hasOnlyWeakSources, answerType);

        List<String> limitations = buildLimitations(
                supportStatus, baseLimitations, hasSources, hasOnlyWeakSources,
                hasUncertainFreshness, hasOnlyDerivative, lacksMaterialDiversity);
        List<String> warnings = buildWarnings(
                requiresHumanValidation, validationMessage, hasOutdatedSources, hasOnlyWeakSources);
        boolean suggestReformulation = !hasSources && isLimitAnswer(supportStatus);
        List<String> nextSteps = buildNextSteps(
                parecerRequirement, hasUncertainFreshness, hasSources, suggestReformulation);

        String confidenceSummary = buildConfidenceSummary(
                supportStatus, answerType, hasOfficialOrLegalAuthority, hasOnlyWeakSources,
                hasOutdatedSources);
        String sourceSummary = buildSourceSummary(
                hasSources, hasOfficialOrLegalAuthority, hasOnlyWeakSources, hasOutdatedSources,
                hasUncertainFreshness, lacksMaterialDiversity);

        FreshnessStatus overallFreshnessStatus =
                hasOutdatedSources ? FreshnessStatus.OUTDATED : FreshnessStatus.UNCERTAIN;

        return new AnswerDecision(
                answerType, parecerRequirement, confidenceSummary,
                limitations, warnings, nextSteps, overallFreshnessStatus, sourceSummary);
    }

    // --- answerType ---

    private AnswerType decideAnswerType(AnswerSupportStatus supportStatus, boolean limitationSignals) {
        if (supportStatus == null) {
            return AnswerType.RESPOSTA_LIMITE;
        }
        return switch (supportStatus) {
            case SUPPORTED -> limitationSignals
                    ? AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES
                    : AnswerType.CONSULTA_DOCUMENTADA;
            case PARTIALLY_SUPPORTED, REQUIRES_HUMAN_REVIEW -> AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES;
            case INSUFFICIENT_CONTEXT, REJECTED_UNSUPPORTED -> AnswerType.RESPOSTA_LIMITE;
        };
    }

    // --- parecerRequirement ---

    private ParecerRequirement decideParecerRequirement(
            AnswerSupportStatus supportStatus, boolean requiresHumanValidation,
            boolean hasSources, boolean hasOutdatedSources, boolean hasOnlyWeakSources,
            AnswerType answerType) {

        ParecerRequirement base;
        if (supportStatus == null) {
            base = ParecerRequirement.SUGGESTED;
        } else {
            base = switch (supportStatus) {
                case SUPPORTED -> ParecerRequirement.NONE;
                case PARTIALLY_SUPPORTED -> ParecerRequirement.SUGGESTED;
                // Ainda há enquadramento útil se houver fontes; senão, prudência máxima.
                case INSUFFICIENT_CONTEXT, REQUIRES_HUMAN_REVIEW ->
                        hasSources ? ParecerRequirement.SUGGESTED : ParecerRequirement.REQUIRED;
                case REJECTED_UNSUPPORTED -> ParecerRequirement.REQUIRED;
            };
        }

        // Escalões só sobem, nunca descem (prudência).
        ParecerRequirement result = base;
        if (requiresHumanValidation) {
            result = escalate(result, ParecerRequirement.SUGGESTED);
        }
        if (hasOutdatedSources || hasOnlyWeakSources) {
            result = escalate(result, ParecerRequirement.SUGGESTED);
        }
        if (answerType == AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES) {
            result = escalate(result, ParecerRequirement.SUGGESTED);
        }
        return result;
    }

    private ParecerRequirement escalate(ParecerRequirement current, ParecerRequirement floor) {
        return current.ordinal() >= floor.ordinal() ? current : floor;
    }

    // --- limitations ---

    private List<String> buildLimitations(
            AnswerSupportStatus supportStatus, List<String> baseLimitations, boolean hasSources,
            boolean hasOnlyWeakSources, boolean hasUncertainFreshness, boolean hasOnlyDerivative,
            boolean lacksMaterialDiversity) {

        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        if (baseLimitations != null) {
            for (String limitation : baseLimitations) {
                if (limitation != null && !limitation.isBlank()) {
                    limitations.add(limitation);
                }
            }
        }

        if (isLimitAnswer(supportStatus)) {
            limitations.add("As fontes disponíveis não permitem fechar uma conclusão fiscal actual segura.");
        } else if (supportStatus != AnswerSupportStatus.SUPPORTED || hasOnlyWeakSources
                || hasUncertainFreshness || hasOnlyDerivative || lacksMaterialDiversity) {
            limitations.add("A resposta baseia-se no suporte documental disponível e deve ser "
                    + "confirmada se aplicada a um caso concreto.");
        }

        if (hasOnlyWeakSources) {
            limitations.add("Foram identificadas apenas fontes de autoridade limitada.");
        }
        if (hasUncertainFreshness && hasSources) {
            limitations.add("A actualidade de uma ou mais fontes não está confirmada.");
        }
        if (hasOnlyDerivative || lacksMaterialDiversity) {
            limitations.add("O suporte documental aparenta assentar em fontes derivadas ou sem "
                    + "diversidade material suficiente.");
        }
        return List.copyOf(limitations);
    }

    // --- warnings ---

    private List<String> buildWarnings(
            boolean requiresHumanValidation, String validationMessage,
            boolean hasOutdatedSources, boolean hasOnlyWeakSources) {

        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (hasOutdatedSources) {
            warnings.add("Existe fonte marcada como desactualizada; deve ser usada apenas como "
                    + "enquadramento histórico, contraste ou alerta.");
        }
        if (hasOnlyWeakSources) {
            warnings.add("As fontes disponíveis não oficiais não devem sustentar sozinhas uma "
                    + "conclusão fiscal actual.");
        }
        if (requiresHumanValidation) {
            warnings.add(validationMessage != null && !validationMessage.isBlank()
                    ? validationMessage
                    : "A questão pode exigir apreciação profissional antes de aplicação a um caso concreto.");
        }
        return List.copyOf(warnings);
    }

    // --- nextSteps ---

    private List<String> buildNextSteps(
            ParecerRequirement parecerRequirement, boolean hasUncertainFreshness, boolean hasSources,
            boolean suggestReformulation) {

        List<String> nextSteps = new ArrayList<>();
        if (parecerRequirement == ParecerRequirement.REQUIRED) {
            nextSteps.add("Submeter Pedido de parecer antes de adoptar uma conclusão fiscal.");
        } else if (parecerRequirement == ParecerRequirement.SUGGESTED) {
            nextSteps.add("Confirmar a aplicação ao caso concreto ou submeter Pedido de parecer.");
        }
        if (hasUncertainFreshness && hasSources) {
            nextSteps.add("Confirmar a legislação ou orientação oficial vigente.");
        }
        // Só sugerimos reformular numa Resposta-limite sem fontes; nunca poluímos o caminho
        // feliz (SUPPORTED sem fontes recuperadas continua a ser uma consulta documentada).
        if (suggestReformulation) {
            nextSteps.add("Reformular a pergunta ou acrescentar factos/documentos relevantes.");
        }
        return List.copyOf(nextSteps);
    }

    // --- confidenceSummary ---

    private String buildConfidenceSummary(
            AnswerSupportStatus supportStatus, AnswerType answerType,
            boolean hasOfficialOrLegalAuthority, boolean hasOnlyWeakSources, boolean hasOutdatedSources) {

        if (supportStatus == null) {
            return "Confiança indeterminada; resposta apresentada com prudência.";
        }
        return switch (supportStatus) {
            case SUPPORTED -> (answerType == AnswerType.CONSULTA_DOCUMENTADA
                    && hasOfficialOrLegalAuthority && !hasOnlyWeakSources && !hasOutdatedSources)
                    ? "Suporte documental suficiente para resposta profissional documentada, "
                        + "sem prejuízo de validação no caso concreto."
                    : "Suporte documental existente, mas com limitações quanto à autoridade, "
                        + "actualidade ou diversidade das fontes.";
            case PARTIALLY_SUPPORTED -> "Suporte documental parcial; a resposta deve ser lida com limitações.";
            case INSUFFICIENT_CONTEXT, REJECTED_UNSUPPORTED ->
                    "Suporte documental insuficiente para conclusão fiscal actual segura.";
            case REQUIRES_HUMAN_REVIEW -> "A resposta automática deve ser lida como enquadramento "
                    + "prudente, podendo justificar Pedido de parecer.";
        };
    }

    // --- sourceSummary ---

    private String buildSourceSummary(
            boolean hasSources, boolean hasOfficialOrLegalAuthority, boolean hasOnlyWeakSources,
            boolean hasOutdatedSources, boolean hasUncertainFreshness, boolean lacksMaterialDiversity) {

        if (!hasSources) {
            return "Sem fontes documentais recuperadas para esta resposta.";
        }
        List<String> parts = new ArrayList<>();
        if (hasOfficialOrLegalAuthority) {
            parts.add("fonte oficial/legal presente");
        }
        if (hasOnlyWeakSources) {
            parts.add("apenas fontes de autoridade limitada");
        }
        if (hasOutdatedSources) {
            parts.add("actualidade desactualizada assinalada");
        }
        if (hasUncertainFreshness) {
            parts.add("actualidade incerta");
        }
        if (lacksMaterialDiversity) {
            parts.add("diversidade material incerta");
        }
        if (parts.isEmpty()) {
            return "Suporte documental baseado nas fontes recuperadas.";
        }
        return "Suporte documental: " + String.join("; ", parts) + ".";
    }

    // --- helpers sobre fontes ---

    private boolean hasSources(List<SourceEvidence> sources) {
        return !sources.isEmpty();
    }

    private boolean hasStrongOrAdequateSource(List<SourceEvidence> sources) {
        return sources.stream().anyMatch(s ->
                s.sourceQuality() == SourceQuality.STRONG || s.sourceQuality() == SourceQuality.ADEQUATE);
    }

    private boolean hasOnlyWeakSources(List<SourceEvidence> sources) {
        return !sources.isEmpty() && sources.stream().allMatch(s ->
                s.sourceQuality() == SourceQuality.WEAK
                        || s.authorityLevel() == AuthorityLevel.EXTERNAL_NON_OFFICIAL);
    }

    private boolean hasOutdatedSources(List<SourceEvidence> sources) {
        return sources.stream().anyMatch(s -> s.freshnessStatus() == FreshnessStatus.OUTDATED);
    }

    private boolean hasUncertainFreshness(List<SourceEvidence> sources) {
        return sources.stream().anyMatch(s -> s.freshnessStatus() == FreshnessStatus.UNCERTAIN);
    }

    private boolean hasOfficialOrLegalAuthority(List<SourceEvidence> sources) {
        return sources.stream().anyMatch(s -> switch (s.authorityLevel()) {
            case LEGAL, OFFICIAL_FAQ, OFFICIAL_ADMINISTRATIVE, JURISPRUDENCE, OFFICIAL_COMPLEMENTARY -> true;
            case INTERNAL_CURATED, EXTERNAL_NON_OFFICIAL -> false;
        });
    }

    private boolean hasOnlyDerivativeReplicatedSources(List<SourceEvidence> sources) {
        return !sources.isEmpty()
                && sources.stream().allMatch(s -> s.sourceRole() == SourceRole.DERIVATIVE_REPLICATED);
    }

    private boolean hasMaterialDiversity(List<SourceEvidence> sources) {
        return sources.stream().anyMatch(s -> s.sourceDiversity() == SourceDiversity.MATERIAL_DIVERSITY);
    }

    private boolean isLimitAnswer(AnswerSupportStatus supportStatus) {
        return supportStatus == null
                || supportStatus == AnswerSupportStatus.INSUFFICIENT_CONTEXT
                || supportStatus == AnswerSupportStatus.REJECTED_UNSUPPORTED;
    }
}
