package com.knowledgeflow.research.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.research.source.ResearchConfiguration;
import com.knowledgeflow.research.source.ResearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Modo integrado desligado por omissão e recusado no arranque enquanto faltarem pré-requisitos (§3.1, §3.5). */
class AskIntegrationGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ResearchConfiguration.class, AskIntegrationGuard.class);

    private static ResearchProperties props(boolean research, boolean integration) {
        ResearchProperties p = new ResearchProperties();
        p.setEnabled(research);
        p.getAskIntegration().setEnabled(integration);
        return p;
    }

    @Test
    void integratedModeIsOffByDefault() {
        assertThat(new ResearchProperties().getAskIntegration().isEnabled()).isFalse();
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ResearchProperties.class).getAskIntegration().isEnabled()).isFalse();
        });
    }

    @Test
    void disabledIntegrationNeverBlocksStartupWhateverTheResearchSwitch() {
        assertThat(AskIntegrationGuard.problems(props(false, false))).isEmpty();
        assertThat(AskIntegrationGuard.problems(props(true, false))).isEmpty();
        runner.withPropertyValues("knowledgeflow.research.enabled=true").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void integrationWithoutResearchIsRefusedWithAllReasons() {
        assertThat(AskIntegrationGuard.problems(props(false, true)))
                .hasSize(2 + AskIntegrationGuard.MISSING_PREREQUISITES.size())
                .first().asString().contains("knowledgeflow.research.enabled");
    }

    @Test
    void integrationIsRefusedWhileSpendControlAndEntailmentBarrierAreMissing() {
        // Barreira desligada: recusada também por isso (A4: a barreira é exigida no modo integrado).
        assertThat(AskIntegrationGuard.problems(props(true, true)))
                .hasSize(1 + AskIntegrationGuard.MISSING_PREREQUISITES.size())
                .anySatisfy(p -> assertThat(p).contains("L6"))
                .anySatisfy(p -> assertThat(p).contains("composição restrita"))
                .anySatisfy(p -> assertThat(p).contains("knowledgeflow.research.entailment.enabled"));
        // Barreira ligada: continua recusada por faltarem o L6 e a composição restrita.
        ResearchProperties withBarrier = props(true, true);
        withBarrier.getEntailment().setEnabled(true);
        assertThat(AskIntegrationGuard.problems(withBarrier))
                .hasSize(AskIntegrationGuard.MISSING_PREREQUISITES.size())
                .noneSatisfy(p -> assertThat(p).contains("entailment"))
                .anySatisfy(p -> assertThat(p).contains("L6"))
                .anySatisfy(p -> assertThat(p).contains("composição restrita"));
        assertThatThrownBy(() -> new AskIntegrationGuard(withBarrier).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AskIntegrationGuard(props(true, true)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Modo integrado");
    }

    @Test
    void applicationContextFailsToStartWithIntegrationEnabled() {
        runner.withPropertyValues("knowledgeflow.research.enabled=true",
                        "knowledgeflow.research.ask-integration.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("L6"));
        runner.withPropertyValues("knowledgeflow.research.ask-integration.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /** Com inicialização preguiçosa global (spring.main.lazy-initialization=true), a guarda corre na mesma. */
    @Test
    void guardRunsEvenWithLazyInitialization() {
        ApplicationContextRunner lazy = runner.withInitializer(
                ctx -> ctx.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        lazy.withPropertyValues("knowledgeflow.research.enabled=true",
                        "knowledgeflow.research.ask-integration.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("L6"));
        lazy.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
