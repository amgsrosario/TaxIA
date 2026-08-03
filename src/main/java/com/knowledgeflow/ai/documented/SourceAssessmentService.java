package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSource;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Avaliação real, simples, determinística e conservadora de uma fonte
 * ({@link AnswerSource}) para o contrato documentado (tarefa D5).
 *
 * <p>Substitui parte dos <em>defaults cegos</em> da D4 por heurísticas baseadas apenas nos
 * campos realmente disponíveis em {@link AnswerSource} ({@code title} e {@code reference}).
 * <strong>Não</strong> há scoring numérico, thresholds, deduplicação semântica, análise de
 * núcleo sofisticada, chamadas a providers externos nem validação online de URLs.
 *
 * <p>Princípios:
 * <ul>
 *   <li><strong>Conservadorismo</strong>: na dúvida, escolher o valor mais prudente
 *       ({@code UNCERTAIN}, {@code MIXED_OR_UNCLEAR}, {@code INTERNAL_CURATED}).</li>
 *   <li><strong>Não inventar dados</strong>: só concluir a partir de evidência textual
 *       clara nos campos disponíveis.</li>
 *   <li><strong>Sem confusão de conceitos</strong>: {@code FreshnessStatus.OUTDATED} é
 *       actualidade da fonte, não {@code KnowledgeCurationStatus.OUTDATED} (C8, regra 25);
 *       nada aqui decide {@code answerType} nem risco agregado (regra 26).</li>
 * </ul>
 */
@Service
public class SourceAssessmentService {

    /** Acrónimos de tribunais como palavras isoladas (evita falsos positivos como "resposta"). */
    private static final Pattern COURT_ACRONYM = Pattern.compile("\\b(sta|tcas|tcan|stj)\\b");

    /** Abreviaturas de códigos/leis fiscais como palavras isoladas. */
    private static final Pattern FISCAL_CODE = Pattern.compile(
            "\\b(cirs|civa|cimi|cimt|cppt|lgt|cis|ciec|irc|ebf|rgit)\\b");

    /** Referência a artigo seguido de número (ex.: "artigo 18", "art. 9"). */
    private static final Pattern ARTICLE_NUMBER = Pattern.compile("\\bart(?:igo)?\\.?\\s*\\d");

    /** "lei n.º 12", "decreto-lei" etc. */
    private static final Pattern LAW_NUMBER = Pattern.compile("\\blei\\s+n");

    /**
     * Deriva a autoridade da fonte a partir de sinais textuais claros em título/referência.
     *
     * <p>Ordem de precedência (do sinal mais distintivo para o mais genérico):
     * jurisprudência, FAQ oficial, orientação administrativa, legislação, fonte oficial
     * complementar, fonte externa não oficial. Sem sinais → {@code INTERNAL_CURATED}
     * (origem mais plausível para conhecimento interno curado).
     */
    public AuthorityLevel assessAuthorityLevel(AnswerSource source) {
        String text = textOf(source);
        if (text.isBlank()) {
            return AuthorityLevel.INTERNAL_CURATED;
        }
        if (containsAny(text, "acórdão", "acordao", "tribunal", "caad", "supremo tribunal",
                "jurisprud") || COURT_ACRONYM.matcher(text).find()) {
            return AuthorityLevel.JURISPRUDENCE;
        }
        if (containsAny(text, "faq", "perguntas frequentes")) {
            return AuthorityLevel.OFFICIAL_FAQ;
        }
        if (containsAny(text, "ofício circulado", "oficio circulado", "informação vinculativa",
                "informacao vinculativa", "instrução", "instrucao", "despacho", "circular",
                "orientação", "orientacao")) {
            return AuthorityLevel.OFFICIAL_ADMINISTRATIVE;
        }
        if (containsAny(text, "código", "codigo", "decreto-lei", "decreto lei",
                "estatuto dos benefícios", "estatuto dos beneficios")
                || FISCAL_CODE.matcher(text).find()
                || ARTICLE_NUMBER.matcher(text).find()
                || LAW_NUMBER.matcher(text).find()) {
            return AuthorityLevel.LEGAL;
        }
        if (containsAny(text, "portal das finanças", "portal das financas",
                "autoridade tributária", "autoridade tributaria", "gov.pt", "dre.pt",
                "diário da república", "diario da republica")) {
            return AuthorityLevel.OFFICIAL_COMPLEMENTARY;
        }
        if (isExternalNonOfficial(text)) {
            return AuthorityLevel.EXTERNAL_NON_OFFICIAL;
        }
        return AuthorityLevel.INTERNAL_CURATED;
    }

    /**
     * Qualidade inicial derivada da autoridade e da presença de referência mínima.
     * Sem scoring; heurística conservadora.
     */
    public SourceQuality assessSourceQuality(AnswerSource source) {
        AuthorityLevel authority = assessAuthorityLevel(source);
        return switch (authority) {
            case LEGAL, OFFICIAL_FAQ, OFFICIAL_ADMINISTRATIVE -> SourceQuality.STRONG;
            case JURISPRUDENCE, OFFICIAL_COMPLEMENTARY -> SourceQuality.ADEQUATE;
            case INTERNAL_CURATED -> hasMeaningfulReference(source)
                    ? SourceQuality.ADEQUATE
                    : SourceQuality.LIMITED;
            case EXTERNAL_NON_OFFICIAL -> SourceQuality.WEAK;
        };
    }

    /**
     * Papel da fonte. Por defeito {@code PRIMARY} (fonte devolvida/usada na resposta actual).
     * Só marca {@code DERIVATIVE_REPLICATED} com evidência textual simples de cópia/derivação.
     * {@code COMPLEMENTARY} fica reservado para fases com visão do conjunto.
     */
    public SourceRole assessSourceRole(AnswerSource source) {
        if (hasDerivationEvidence(textOf(source))) {
            return SourceRole.DERIVATIVE_REPLICATED;
        }
        return SourceRole.PRIMARY;
    }

    /**
     * Diversidade material vista ao nível de uma fonte isolada (o mapper não tem, nesta
     * fase, visão do conjunto). {@code SAME_CORE} só com evidência directa de derivação;
     * {@code MATERIAL_DIVERSITY} quando há núcleo próprio claro; caso contrário
     * {@code MIXED_OR_UNCLEAR}.
     */
    public SourceDiversity assessSourceDiversity(AnswerSource source) {
        if (hasDerivationEvidence(textOf(source))) {
            return SourceDiversity.SAME_CORE;
        }
        if (hasMeaningfulReference(source)) {
            return SourceDiversity.MATERIAL_DIVERSITY;
        }
        return SourceDiversity.MIXED_OR_UNCLEAR;
    }

    /**
     * Actualidade. Default {@code UNCERTAIN}; só {@code OUTDATED} com marcadores explícitos
     * de revogação/caducidade/substituição. Nunca usa data isolada nem chama a web (regra 12).
     */
    public FreshnessStatus assessFreshnessStatus(AnswerSource source) {
        String text = textOf(source);
        if (containsAny(text, "revogado", "revogada", "caducado", "caducada",
                "substituído", "substituida", "substituído por", "desactualizado",
                "desatualizado", "sem efeito")) {
            return FreshnessStatus.OUTDATED;
        }
        return FreshnessStatus.UNCERTAIN;
    }

    /**
     * Identificador de núcleo estável, sem semântica avançada. Prioridade: referência legal
     * normalizada → título normalizado → {@code null} se nada utilizável.
     */
    public String buildSourceCore(AnswerSource source) {
        if (source == null) {
            return null;
        }
        String reference = normalize(source.reference());
        if (!reference.isBlank()) {
            return reference;
        }
        String title = normalize(source.title());
        return title.isBlank() ? null : title;
    }

    /** Grupo de diversidade. Nesta fase é igual ao núcleo ({@link #buildSourceCore}). */
    public String buildSourceDiversityGroup(AnswerSource source) {
        return buildSourceCore(source);
    }

    // --- helpers ---

    private String textOf(AnswerSource source) {
        if (source == null) {
            return "";
        }
        String title = source.title() != null ? source.title() : "";
        String reference = source.reference() != null ? source.reference() : "";
        return (title + " " + reference).toLowerCase().trim();
    }

    private boolean hasMeaningfulReference(AnswerSource source) {
        return source != null && source.reference() != null && !source.reference().isBlank();
    }

    private boolean hasDerivationEvidence(String text) {
        return containsAny(text, "cópia de", "copia de", "reprodução de", "reproducao de",
                "extraído de", "extraido de", "adaptado de", "resumo de", "baseado em");
    }

    private boolean isExternalNonOfficial(String text) {
        // URL http(s) que não pertence a domínios oficiais conhecidos.
        if (!text.contains("http")) {
            return false;
        }
        return !containsAny(text, "gov.pt", "portaldasfinancas", "portal das finanças",
                "portal das financas", "dre.pt");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Normalização mínima: apara, colapsa espaços e passa a minúsculas. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
