package com.knowledgeflow.ai.grounding.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** Filtro candidato a candidato (M4-SCOPE): DOCUMENT inalterado, fail-closed, batch e ordem. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FiscalScopeFilterTest {

    private static final UUID QA_PENSION = UUID.fromString("00000000-0000-0000-0000-000000005795");
    private static final UUID QA_VAT = UUID.fromString("00000000-0000-0000-0000-00000000c1fa");
    private static final UUID QA_RENT = UUID.fromString("00000000-0000-0000-0000-000000005930");

    @Mock private KnowledgeQuestionAnswerRepository repository;

    private FiscalScopeFilter filter;

    @BeforeEach
    void setUp() {
        FiscalScopeClassifier classifier = new FiscalScopeClassifier();
        filter = new FiscalScopeFilter(new CandidateScopeLoader(repository, classifier), classifier,
                new ScopeCompatibilityGate(), new ScopeGateProperties(true));
    }

    @Test
    void rejectsContradictingCandidates_keepsTheRest_inRagOrder() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(pensionRow(), vatRow(), rentRow()));

        List<RetrievedCase> kept = filter.filter("Durante quanto tempo tenho de conservar os documentos de IRS?",
                List.of(qa("CIVA", QA_VAT), qa("5795", QA_PENSION), qa("5930", QA_RENT)));

        assertThat(kept).isEmpty();

        List<RetrievedCase> pension = filter.filter("Recebo duas pensões com retenção a 0%. Posso pedir retenção mensal?",
                List.of(qa("CIVA", QA_VAT), qa("5795", QA_PENSION), qa("5930", QA_RENT)));
        assertThat(pension).extracting(RetrievedCase::title).containsExactly("5795");
    }

    @Test
    void loadsAllCandidateScopesInOneBatch() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(pensionRow(), vatRow(), rentRow()));

        filter.filter("Pergunta sobre IVA?", List.of(qa("A", QA_VAT), qa("B", QA_PENSION), qa("C", QA_RENT),
                qa("A bis", QA_VAT)));

        verify(repository, times(1)).findScopeRowsByIdIn(Set.of(QA_VAT, QA_PENSION, QA_RENT));
    }

    @Test
    void documentCandidates_passUnchanged_withoutLoadingAnything() {
        RetrievedCase document = new RetrievedCase("Documento", "?", "Texto.", 0.95, SourceKind.DOCUMENT, null);

        assertThat(filter.filter("Qual a taxa de IRC?", List.of(document))).containsExactly(document);
        verifyNoInteractions(repository);
    }

    @Test
    void documentCandidates_survive_evenWhenQaLoadingFails() {
        RetrievedCase document = new RetrievedCase("Documento", "?", "Texto.", 0.95, SourceKind.DOCUMENT, null);
        when(repository.findScopeRowsByIdIn(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(filter.filter("Pergunta?", List.of(qa("5795", QA_PENSION), document))).containsExactly(document);
    }

    @Test
    void loaderFailure_rejectsEveryQaCandidate() {
        when(repository.findScopeRowsByIdIn(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(filter.filter("Pensões e retenção?", List.of(qa("5795", QA_PENSION), qa("5930", QA_RENT))))
                .isEmpty();
    }

    @Test
    void missingQaRow_rejectsThatCandidateOnly() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(pensionRow()));

        assertThat(filter.filter("Posso pedir retenção na minha pensão?",
                List.of(qa("5795", QA_PENSION), qa("Desaparecida", QA_RENT))))
                .extracting(RetrievedCase::title).containsExactly("5795");
    }

    @Test
    void classifierFailure_isFailClosed() {
        FiscalScopeClassifier broken = new FiscalScopeClassifier() {
            @Override
            public FiscalScope classify(String text) {
                throw new IllegalStateException("boom");
            }
        };
        FiscalScopeFilter failing = new FiscalScopeFilter(new CandidateScopeLoader(repository, broken), broken,
                new ScopeCompatibilityGate(), new ScopeGateProperties(true));

        assertThat(failing.filter("Pergunta?", List.of(qa("5795", QA_PENSION)))).isEmpty();
    }

    @Test
    void questionSilentInEveryDimension_keepsEveryCandidate() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(pensionRow(), vatRow(), rentRow()));

        assertThat(filter.filter("E os prazos?", List.of(qa("A", QA_VAT), qa("B", QA_PENSION), qa("C", QA_RENT))))
                .hasSize(3);
    }

    @Test
    void qaWithoutTopicOrSubtopic_usesOriginalQuestion_whenNormalizedIsMissing() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(new KnowledgeQaScopeRow(QA_VAT, null, null,
                "Quanto tempo devo conservar as facturas de IVA?", null)));

        assertThat(filter.filter("Qual a taxa de IRC?", List.of(qa("CIVA", QA_VAT)))).isEmpty();
        assertThat(filter.filter("Prazo de conservação para IVA?", List.of(qa("CIVA", QA_VAT)))).hasSize(1);
    }

    @Test
    void disabledFilter_returnsCandidatesUnchanged() {
        List<RetrievedCase> candidates = List.of(qa("5795", QA_PENSION));

        assertThat(FiscalScopeFilter.disabled().filter("Qual a taxa de IRC?", candidates)).isSameAs(candidates);
        assertThat(new FiscalScopeFilter(new CandidateScopeLoader(repository, new FiscalScopeClassifier()),
                new FiscalScopeClassifier(), new ScopeCompatibilityGate(), new ScopeGateProperties(false))
                .filter("Qual a taxa de IRC?", candidates)).isSameAs(candidates);
        verifyNoInteractions(repository);
    }

    @Test
    void logs_neverContainTheQuestionText(CapturedOutput output) {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(pensionRow()));

        filter.filter("Pergunta confidencial do cliente Zé Ninguém sobre pensões e retenção?",
                List.of(qa("5795", QA_PENSION)));

        assertThat(output.getAll()).contains("Scope gate: received=1")
                .doesNotContain("confidencial", "Ninguém");
    }

    @Test
    void qaWithBlankQuestion_isRejectedAsMissingMetadata() {
        when(repository.findScopeRowsByIdIn(any())).thenReturn(List.of(
                new KnowledgeQaScopeRow(QA_PENSION, KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO, null, " ", " ")));

        assertThat(filter.filter("E os prazos?", List.of(qa("Vazia", QA_PENSION)))).isEmpty();
    }

    @Test
    void nullOrEmptyCandidates_returnEmpty() {
        assertThat(filter.filter("P?", null)).isEmpty();
        assertThat(filter.filter("P?", List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    private static RetrievedCase qa(String title, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta " + title + ".", 0.9, SourceKind.KNOWLEDGE_QA, qaId);
    }

    private static KnowledgeQaScopeRow pensionRow() {
        return new KnowledgeQaScopeRow(QA_PENSION, KnowledgeTopic.IRS, "Retenção na fonte — categoria H",
                "Um pensionista pode pedir retenção mensal?", null);
    }

    private static KnowledgeQaScopeRow vatRow() {
        return new KnowledgeQaScopeRow(QA_VAT, KnowledgeTopic.IVA, null,
                "Durante quanto tempo deve um sujeito passivo de IVA conservar os registos?", null);
    }

    private static KnowledgeQaScopeRow rentRow() {
        return new KnowledgeQaScopeRow(QA_RENT, KnowledgeTopic.IRS, "Categoria F — gastos dedutíveis",
                "Que despesas podem ser deduzidas aos rendimentos prediais?", null);
    }
}
