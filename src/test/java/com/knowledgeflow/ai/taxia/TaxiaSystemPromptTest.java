package com.knowledgeflow.ai.taxia;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Anti-regression for the behavioural v2 system prompt.
 *
 * <p>Asserts the stable behavioural content is present and — critically — that no volatile
 * fiscal fact leaks into this compiled constant (rates, currency, thresholds, code names,
 * form numbers, periodicity, legal instruments). These checks are intentionally coarse
 * (substring / token presence) to stay behavioural and avoid brittle whitespace assertions.
 */
class TaxiaSystemPromptTest {

    private static final String PROMPT = TaxiaSystemPrompt.FISCAL_ASSISTANT;

    @Test
    void preservesPersonaLanguageAndProfessionalism() {
        assertThat(PROMPT).contains("direito fiscal português e europeu");
        assertThat(PROMPT).contains("português de Portugal");
        assertThat(PROMPT).contains("profissional");
    }

    @Test
    void preservesUncertaintyBehaviour() {
        assertThat(PROMPT).contains("incerteza");
    }

    @Test
    void addsContextDiscipline() {
        assertThat(PROMPT).contains("contexto");
        assertThat(PROMPT).contains("fontes");
    }

    @Test
    void addsAntiHallucination() {
        assertThat(PROMPT).contains("Não inventes");
        assertThat(PROMPT).contains("lacunas factuais");
    }

    @Test
    void addsMissingInformationRequest() {
        assertThat(PROMPT).contains("pede-os");
    }

    @Test
    void addsGeneralRuleVsConcreteCaseDistinction() {
        assertThat(PROMPT).contains("regra geral");
        assertThat(PROMPT).contains("caso concreto");
    }

    @Test
    void addsLimitationsAndProfessionalCaution() {
        assertThat(PROMPT).contains("limitações");
        assertThat(PROMPT).contains("validação profissional ou humana");
    }

    @Test
    void doesNotClaimExternalSources() {
        assertThat(PROMPT).contains("fontes externas");
    }

    @Test
    void containsNoConcreteFiscalFacts() {
        // No currency, percentage, numeric threshold, code names, form numbers, periodicity,
        // or legal-instrument facts may be hardcoded here — those belong to governed RAG.
        assertThat(PROMPT).doesNotContain("%");
        assertThat(PROMPT).doesNotContain("€");
        assertThat(PROMPT).doesNotContain("650");
        assertThat(PROMPT).doesNotContain("CIVA");
        assertThat(PROMPT).doesNotContain("CIRS");
        assertThat(PROMPT).doesNotContain("CIRC");
        assertThat(PROMPT).doesNotContain("Modelo ");
        assertThat(PROMPT).doesNotContain("periodicidade");
        assertThat(PROMPT).doesNotContain("volume de negócios");
        assertThat(PROMPT).doesNotContain("decreto-lei");
        assertThat(PROMPT).doesNotContain("portaria");
        assertThat(PROMPT).doesNotContain("art.");
    }

    @Test
    void staysCompact() {
        // Behavioural v2 must stay short: a handful of lines, not a legal treatise.
        long nonBlankLines = PROMPT.lines().filter(l -> !l.isBlank()).count();
        assertThat(nonBlankLines).isBetween(8L, 15L);
    }
}
