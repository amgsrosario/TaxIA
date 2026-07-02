package com.knowledgeflow.ai.grounding;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ContextSufficiencyEvaluator {

    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "autoridade tributária",
            "notificação da at",
            "impugnação",
            "contencioso",
            "recurso hierárquico",
            "imóvel",
            "imobiliário",
            "ofício circulado",
            "jurisprudência",
            "inspeção tributária",
            "regularização de iva",
            "transmissão de imóvel"
    );

    private final GroundingProperties props;

    public ContextSufficiencyEvaluator(GroundingProperties props) {
        this.props = props;
    }

    public ContextSufficiencyAssessment evaluate(List<RetrievedCase> cases, String question) {
        // Human-review detection is independent of context sufficiency:
        // a high-risk question must always flag human review, even when context is insufficient.
        boolean humanReview = requiresHumanReview(question);

        if (cases.isEmpty()) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    "Nenhum caso validado encontrado na base de conhecimento.",
                    List.of("documentação relevante para a questão colocada"),
                    0, 0, 0.0, humanReview);
        }

        List<RetrievedCase> withContent = cases.stream()
                .filter(c -> c.content() != null && !c.content().isBlank())
                .toList();

        if (withContent.isEmpty()) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    "Os fragmentos recuperados não contêm conteúdo validado.",
                    List.of("conteúdo documental validado"),
                    0, cases.size(), 0.0, humanReview);
        }

        int distinctSources = (int) withContent.stream()
                .map(RetrievedCase::title)
                .distinct()
                .count();

        double bestScore = withContent.stream()
                .mapToDouble(RetrievedCase::similarity)
                .max()
                .orElse(0.0);

        if (withContent.size() < props.minimumFragments()) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    "Fragmentos insuficientes (%d < %d mínimo).".formatted(withContent.size(), props.minimumFragments()),
                    List.of("mais fragmentos documentais validados"),
                    distinctSources, withContent.size(), bestScore, humanReview);
        }

        if (distinctSources < props.minimumDistinctSources()) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    "Fontes distintas insuficientes (%d < %d mínimo).".formatted(distinctSources, props.minimumDistinctSources()),
                    List.of("fontes documentais adicionais"),
                    distinctSources, withContent.size(), bestScore, humanReview);
        }

        if (bestScore < props.minimumRelevanceScore()) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                    "Relevância insuficiente (%.2f < %.2f mínimo).".formatted(bestScore, props.minimumRelevanceScore()),
                    List.of("documentação com maior relevância para a questão"),
                    distinctSources, withContent.size(), bestScore, humanReview);
        }

        if (humanReview) {
            return new ContextSufficiencyAssessment(
                    AnswerSupportStatus.REQUIRES_HUMAN_REVIEW,
                    "Contexto disponível mas a questão envolve matéria de risco elevado que requer validação humana.",
                    List.of(),
                    distinctSources, withContent.size(), bestScore, true);
        }

        return new ContextSufficiencyAssessment(
                AnswerSupportStatus.SUPPORTED,
                "Contexto suficiente com %d fonte(s) e %d fragmento(s).".formatted(distinctSources, withContent.size()),
                List.of(),
                distinctSources, withContent.size(), bestScore, false);
    }

    private boolean requiresHumanReview(String question) {
        if (question == null) return false;
        String lower = question.toLowerCase();
        return HIGH_RISK_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
