package com.knowledgeflow.ai.documented;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fixtures de actualidade (C8, ADR-008): relógio fixo e factos de fonte verificada. Testes que não
 * são sobre actualidade usam fontes verificadas (CURRENT) para isolar o que testam; sem verificação
 * uma fonte é UNCERTAIN, o que agora limita a resposta.
 */
public final class FreshnessFixtures {

    /** 2026-10-13 10:00 UTC (11:00 em Lisboa). */
    public static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final SourceFreshnessPolicy POLICY = new SourceFreshnessPolicy(CLOCK);

    private FreshnessFixtures() {
    }

    /** Factos de uma fonte verificada hoje (legislação com versão consolidada confirmada). */
    public static SourceFreshnessPolicy.Facts verified(KnowledgeSourceType type, String title, String ref) {
        return new SourceFreshnessPolicy.Facts(type, title, ref, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                true, type == KnowledgeSourceType.LEGISLATION, false, null, false);
    }

    public static ResolvedAnswerSource.CuratedSource verifiedLegislation(java.util.UUID id, String title, String ref, String url) {
        return new ResolvedAnswerSource.CuratedSource(id, "LEGISLATION", title, ref, url,
                verified(KnowledgeSourceType.LEGISLATION, title, ref));
    }

    public static DocumentedTaxiaAnswerMapper mapper() {
        return new DocumentedTaxiaAnswerMapper(new SourceAssessmentService(), new AnswerDecisionService(),
                new InternalDiagnosticsBuilder(), POLICY);
    }
}
