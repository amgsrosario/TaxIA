package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Âmbito temporal de conclusões fundadas numa marca de revogação (KF-M02/CORR-11). */
class TemporalScopeTest {

    @Test
    @DisplayName("Afirmação sobre marca de revogação: só a revogação actual; aplicabilidade, efeitos e tempo excedem a evidência")
    void statementLimitedToCurrentRevocation() {
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 do artigo 78.º do CIRS foi revogado e não se aplica")).isTrue();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 do artigo 78.º do CIRS foi revogado em 2015")).isTrue();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 está revogado e deixou de produzir efeitos")).isTrue();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 está revogado desde a lei anterior")).isTrue();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 revogado não pode ser invocado")).isTrue();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 do artigo 78.º do CIRS está revogado")).isFalse();
        assertThat(TemporalScope.exceedsRevocationEvidence("O n.º 4 do artigo 78.º do CIRS foi revogado")).isFalse();
    }

    @Test
    @DisplayName("CORR-12: a resposta de formato restrito contém só o estado actual verificado, o texto oficial, a fonte e a limitação")
    void restrictedAnswerHasOnlyVerifiedContent() {
        String answer = TemporalScope.restrictedAnswer(java.util.List.of(new TemporalScope.RevocationItem(
                "O n.º 4 do artigo 78.º do CIRS está revogado.", "4 - (Revogado.)", "artigo 78.º do CIRS",
                "https://info.portaldasfinancas.gov.pt/x", java.time.LocalDate.of(2026, 10, 28))));
        assertThat(answer).isEqualTo(TemporalScope.RESTRICTED_HEADER + "\n"
                + "- O n.º 4 do artigo 78.º do CIRS está revogado. Texto oficial: «4 - (Revogado.)» (artigo 78.º do CIRS, "
                + "https://info.portaldasfinancas.gov.pt/x, consultado em 2026-10-28).\n"
                + TemporalScope.LIMITATION + "\n" + TemporalScope.RESTRICTED_CLOSING);
        assertThat(TemporalScope.RESTRICTED_HEADER + TemporalScope.RESTRICTED_CLOSING).doesNotContainPattern("\\d");
    }

    @Test
    @DisplayName("A limitação não presume datas nem diplomas e declara a impossibilidade de concluir sobre períodos anteriores")
    void limitationHasNoPresumedFacts() {
        assertThat(TemporalScope.LIMITATION).doesNotContainPattern("\\d").contains("actualmente revogada")
                .contains("não indica a data nem o diploma da revogação")
                .contains("Não é possível concluir").contains("períodos anteriores à revogação");
    }
}
