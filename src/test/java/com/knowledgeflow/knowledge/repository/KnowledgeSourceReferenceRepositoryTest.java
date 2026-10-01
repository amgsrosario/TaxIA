package com.knowledgeflow.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Query multi-Q&amp;A de fontes curadas (M3), em H2: uma só query, fora de qualquer transacção (como no
 * pipeline, com open-in-view desligado), devolvendo linhas só de leitura com o id da Q&amp;A.
 */
@ActiveProfiles("test")
@SpringBootTest
class KnowledgeSourceReferenceRepositoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;

    private KnowledgeQuestionAnswer qa1;
    private KnowledgeQuestionAnswer qa2;
    private KnowledgeQuestionAnswer qa3;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        Organization org = organizationRepository.save(new Organization("Org Fontes M3", null));
        qa1 = qaRepository.save(new KnowledgeQuestionAnswer(org, "Pergunta 1?", "Resposta 1.", "m3-test", "M3-1"));
        qa2 = qaRepository.save(new KnowledgeQuestionAnswer(org, "Pergunta 2?", "Resposta 2.", "m3-test", "M3-2"));
        qa3 = qaRepository.save(new KnowledgeQuestionAnswer(org, "Pergunta 3?", "Resposta 3.", "m3-test", "M3-3"));
        source(qa1, KnowledgeSourceType.LEGISLATION, "Código do IRS — Artigo 41.º", "CIRS, art. 41.º",
                "https://info.portaldasfinancas.gov.pt/irs41.aspx");
        source(qa1, KnowledgeSourceType.OFFICIAL_FAQ, "Portal das Finanças — FAQ 5930", "CIRS, art. 41.º", null);
        source(qa2, KnowledgeSourceType.LEGISLATION, "Código do IMI — Artigo 135.º-D", "CIMI, art. 135.º-D", null);
        source(qa3, KnowledgeSourceType.LEGISLATION, "Código do IVA", "artigo 52.º, n.º 1", null);
    }

    @Test
    void findRowsByQuestionAnswerIdIn_returnsOnlyRequestedQas_withAllFields() {
        List<KnowledgeSourceReferenceRow> rows =
                sourceRepository.findRowsByQuestionAnswerIdIn(Set.of(qa1.getId(), qa2.getId()));

        assertThat(rows).hasSize(3)
                .allSatisfy(r -> assertThat(r.questionAnswerId()).isIn(qa1.getId(), qa2.getId()))
                .allSatisfy(r -> assertThat(r.id()).isNotNull())
                .allSatisfy(r -> assertThat(r.createdAt()).isNotNull());
        assertThat(rows).filteredOn(r -> r.questionAnswerId().equals(qa1.getId()))
                .extracting(KnowledgeSourceReferenceRow::title)
                .containsExactlyInAnyOrder("Código do IRS — Artigo 41.º", "Portal das Finanças — FAQ 5930");
        assertThat(rows).filteredOn(r -> r.sourceType() == KnowledgeSourceType.LEGISLATION
                        && r.questionAnswerId().equals(qa1.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.legalReference()).isEqualTo("CIRS, art. 41.º");
                    assertThat(r.url()).isEqualTo("https://info.portaldasfinancas.gov.pt/irs41.aspx");
                });
        assertThat(rows).extracting(KnowledgeSourceReferenceRow::questionAnswerId).doesNotContain(qa3.getId());
    }

    @Test
    void findRowsByQuestionAnswerIdIn_qaWithoutSources_returnsNothing() {
        KnowledgeQuestionAnswer withoutSources = qaRepository.save(new KnowledgeQuestionAnswer(
                qa1.getOrganization(), "Sem fontes?", "Resposta.", "m3-test", "M3-4"));

        assertThat(sourceRepository.findRowsByQuestionAnswerIdIn(Set.of(withoutSources.getId()))).isEmpty();
    }

    private void source(KnowledgeQuestionAnswer qa, KnowledgeSourceType type, String title,
            String legalReference, String url) {
        KnowledgeSourceReference ref = new KnowledgeSourceReference(qa, type, title);
        ref.update(type, title, legalReference, url, null, null, null, null, null);
        sourceRepository.save(ref);
    }
}
