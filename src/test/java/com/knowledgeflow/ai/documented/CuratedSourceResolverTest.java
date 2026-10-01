package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CuratedSourceResolverTest {

    private static final UUID QA_1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID QA_2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 7, 24, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock private KnowledgeSourceReferenceRepository sourceRepository;

    private CuratedSourceResolver resolver() {
        return new CuratedSourceResolver(sourceRepository);
    }

    @Test
    void singleQa_resolvesItsCuratedSources_carryingIdsInternally() {
        AnswerSource qa = qaSource("Que despesas são dedutíveis?", QA_1);
        KnowledgeSourceReferenceRow law = row(QA_1, 1, KnowledgeSourceType.LEGISLATION,
                "Código do IRS — Artigo 41.º", "CIRS, art. 41.º", "https://info.portaldasfinancas.gov.pt/irs41", T0);
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(law));

        List<ResolvedAnswerSource> resolved = resolver().resolve(List.of(qa));

        assertThat(resolved).hasSize(1);
        ResolvedAnswerSource only = resolved.get(0);
        assertThat(only.isCurated()).isTrue();
        assertThat(only.origin()).isSameAs(qa);
        assertThat(only.curated().id()).isEqualTo(law.id());
        assertThat(only.curated().sourceType()).isEqualTo("LEGISLATION");
        assertThat(only.curated().title()).isEqualTo("Código do IRS — Artigo 41.º");
        assertThat(only.curated().legalReference()).isEqualTo("CIRS, art. 41.º");
        assertThat(only.curated().url()).isEqualTo("https://info.portaldasfinancas.gov.pt/irs41");
    }

    @Test
    void severalQas_loadAllSourcesInOneQuery_inGroundingOrder() {
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(
                row(QA_2, 3, KnowledgeSourceType.OFFICIAL_FAQ, "FAQ B", "CIMI, art. 135.º-D", "https://x.gov.pt/b", T0),
                row(QA_1, 1, KnowledgeSourceType.LEGISLATION, "Lei A", "CIRS, art. 41.º", "https://x.gov.pt/a", T0)));

        List<ResolvedAnswerSource> resolved = resolver().resolve(
                List.of(qaSource("Pergunta A", QA_1), qaSource("Pergunta B", QA_2)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(sourceRepository, times(1)).findRowsByQuestionAnswerIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactly(QA_1, QA_2);
        assertThat(resolved).extracting(r -> r.curated().title()).containsExactly("Lei A", "FAQ B");
    }

    @Test
    void sourceWithoutQaId_fallsBack_withoutQuerying() {
        AnswerSource document = new AnswerSource("Caso de conhecimento", "Caso de conhecimento", 0.8);

        List<ResolvedAnswerSource> resolved = resolver().resolve(List.of(document));

        assertThat(resolved).containsExactly(ResolvedAnswerSource.fallback(document));
        verifyNoInteractions(sourceRepository);
    }

    @Test
    void qaWithoutCuratedSources_fallsBack() {
        AnswerSource qa = qaSource("Pergunta sem fontes", QA_1);
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of());

        assertThat(resolver().resolve(List.of(qa))).containsExactly(ResolvedAnswerSource.fallback(qa));
    }

    @Test
    void noGroundedSources_resolvesNothing_withoutQuerying() {
        assertThat(resolver().resolve(List.of())).isEmpty();
        assertThat(resolver().resolve(null)).isEmpty();
        verifyNoInteractions(sourceRepository);
    }

    @Test
    void sameUrlAcrossQas_keepsFirstOccurrenceOnly() {
        String sharedUrl = "https://info.portaldasfinancas.gov.pt/civa19";
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(
                row(QA_1, 1, KnowledgeSourceType.LEGISLATION, "CIVA art. 19.º (Q&A 1)", "CIVA, art. 19.º", sharedUrl, T0),
                row(QA_2, 2, KnowledgeSourceType.LEGISLATION, "CIVA art. 19.º (Q&A 2)", "CIVA, art. 19.º", " " + sharedUrl + " ", T0),
                row(QA_2, 3, KnowledgeSourceType.OFFICIAL_FAQ, "FAQ 0959", "CIVA, arts. 19.º a 21.º", "https://x.gov.pt/0959", T0)));

        List<ResolvedAnswerSource> resolved = resolver().resolve(
                List.of(qaSource("Pergunta 1", QA_1), qaSource("Pergunta 2", QA_2)));

        assertThat(resolved).extracting(r -> r.curated().title())
                .containsExactly("CIVA art. 19.º (Q&A 1)", "FAQ 0959");
        assertThat(resolved.get(0).origin().sourceQaId()).isEqualTo(QA_1);
    }

    @Test
    void withoutUrl_dedupsByTypeReferenceAndTitle_butKeepsLegallyDistinctSources() {
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(
                row(QA_1, 1, KnowledgeSourceType.LEGISLATION, "Código do IVA", "artigo 52.º, n.º 1", null, T0),
                row(QA_2, 2, KnowledgeSourceType.LEGISLATION, " código   do IVA ", "Artigo 52.º, n.º 1", null, T0),
                row(QA_2, 3, KnowledgeSourceType.LEGISLATION, "Código do IVA", "artigo 52.º, n.º 2", null, T0),
                row(QA_2, 4, KnowledgeSourceType.OFFICIAL_FAQ, "Código do IVA", "artigo 52.º, n.º 1", null, T0)));

        List<ResolvedAnswerSource> resolved = resolver().resolve(
                List.of(qaSource("Pergunta 1", QA_1), qaSource("Pergunta 2", QA_2)));

        // Mesmo tipo + referência + título (normalizados) colapsa; referência ou tipo diferentes não.
        assertThat(resolved).extracting(r -> r.curated().id())
                .containsExactly(id(1), id(3), id(4));
    }

    @Test
    void withinQa_ordersByCreatedAtThenTitleThenId_independentlyOfDatabaseOrder() {
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(
                row(QA_1, 5, KnowledgeSourceType.OTHER, "Sem data", null, "https://x.gov.pt/5", null),
                row(QA_1, 4, KnowledgeSourceType.OTHER, "B", null, "https://x.gov.pt/4", T0),
                row(QA_1, 3, KnowledgeSourceType.OTHER, "a", null, "https://x.gov.pt/3", T0),
                row(QA_1, 2, KnowledgeSourceType.OTHER, "Mesmo", null, "https://x.gov.pt/2", T0.minusDays(1)),
                row(QA_1, 1, KnowledgeSourceType.OTHER, "Mesmo", null, "https://x.gov.pt/1", T0.minusDays(1))));

        List<ResolvedAnswerSource> resolved = resolver().resolve(List.of(qaSource("Pergunta", QA_1)));

        // createdAt (nulo no fim) → título normalizado → id.
        assertThat(resolved).extracting(r -> r.curated().id())
                .containsExactly(id(1), id(2), id(3), id(4), id(5));
    }

    @Test
    void sameQaTwiceInGrounding_isExpandedOnce() {
        when(sourceRepository.findRowsByQuestionAnswerIdIn(anyCollection())).thenReturn(List.of(
                row(QA_1, 1, KnowledgeSourceType.LEGISLATION, "Lei", "CIRS, art. 41.º", "https://x.gov.pt/a", T0)));

        List<ResolvedAnswerSource> resolved = resolver().resolve(
                List.of(qaSource("Título 1", QA_1), qaSource("Título 2", QA_1)));

        assertThat(resolved).hasSize(1);
    }

    private static AnswerSource qaSource(String title, UUID qaId) {
        return new AnswerSource(title, title, 0.9, qaId);
    }

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private static KnowledgeSourceReferenceRow row(UUID qaId, int n, KnowledgeSourceType type, String title,
            String legalReference, String url, OffsetDateTime createdAt) {
        return new KnowledgeSourceReferenceRow(qaId, id(n), type, title, legalReference, url, createdAt);
    }
}
