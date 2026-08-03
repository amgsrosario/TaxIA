package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Testes do resolvedor simples e determinístico {@link VisibilityLevelResolver} (tarefa D8).
 *
 * <p>D8 centraliza a escolha da lente de projecção por endpoint/contexto; não implementa
 * autorização nem lê roles.
 */
class VisibilityLevelResolverTest {

    private final VisibilityLevelResolver resolver = new VisibilityLevelResolver();

    @Test
    void adminAsk_resolvesToInternal() {
        assertThat(resolver.resolveForAdminAsk()).isEqualTo(VisibilityLevel.INTERNAL);
    }

    @Test
    void externalProfessional_resolvesToExternal() {
        assertThat(resolver.resolveForExternalProfessional()).isEqualTo(VisibilityLevel.EXTERNAL);
    }

    @Test
    void demo_resolvesToDemo() {
        assertThat(resolver.resolveForDemo()).isEqualTo(VisibilityLevel.DEMO);
    }

    @Test
    void curation_resolvesToCurationOnly() {
        assertThat(resolver.resolveForCuration()).isEqualTo(VisibilityLevel.CURATION_ONLY);
    }

    @Test
    void resolution_isDeterministic_acrossCalls() {
        assertThat(resolver.resolveForAdminAsk()).isEqualTo(resolver.resolveForAdminAsk());
    }
}
