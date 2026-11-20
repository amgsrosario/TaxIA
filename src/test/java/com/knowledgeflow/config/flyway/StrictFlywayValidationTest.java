package com.knowledgeflow.config.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Comportamento comprovado da validação Flyway numa base descartável (H2 em memória), com migrações de ensaio que
 * reproduzem a situação da ADR-010-A: uma base com uma versão reservada ao piloto (V23) aplicada e que o
 * {@code main} não tem.
 */
class StrictFlywayValidationTest {

    @TempDir Path pilot;
    @TempDir Path mainUpToV22;
    @TempDir Path mainWithV25;

    private static void write(Path dir, String name, String sql) throws Exception {
        Files.writeString(dir.resolve(name), sql);
    }

    private static String newDatabase() {
        return "jdbc:h2:mem:fw" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    /** Base com V20, V22 e V23 (do «piloto») aplicadas. */
    private String pilotDatabase() throws Exception {
        write(pilot, "V20__base.sql", "create table t20 (id int);");
        write(pilot, "V22__entailment.sql", "create table t22 (id int);");
        write(pilot, "V23__model_spend_ledger.sql", "create table t23 (id int);");
        String url = newDatabase();
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + pilot).load().migrate();
        write(mainUpToV22, "V20__base.sql", "create table t20 (id int);");
        write(mainUpToV22, "V22__entailment.sql", "create table t22 (id int);");
        write(mainWithV25, "V20__base.sql", "create table t20 (id int);");
        write(mainWithV25, "V22__entailment.sql", "create table t22 (id int);");
        write(mainWithV25, "V25__ask_tickets.sql", "create table t25 (id int);");
        return url;
    }

    private static FluentConfiguration config(String url, Path locations, boolean strict) {
        FluentConfiguration c = Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + locations);
        if (strict) new StrictFlywayValidation(new MockEnvironment()).customize(c);
        return c;
    }

    @Test
    void customizerIgnoresNoMigrationType() {
        FluentConfiguration c = Flyway.configure();
        assertThat(c.getIgnoreMigrationPatterns()).isNotEmpty(); // omissão do Flyway: *:future
        new StrictFlywayValidation(new MockEnvironment()).customize(c);
        assertThat(c.getIgnoreMigrationPatterns()).isEmpty();
    }

    @Test
    void explicitPropertyPrevailsOverTheStrictDefault() {
        FluentConfiguration c = Flyway.configure().ignoreMigrationPatterns("*:missing");
        new StrictFlywayValidation(new MockEnvironment().withProperty(StrictFlywayValidation.PROPERTY, "*:missing"))
                .customize(c);
        assertThat(c.getIgnoreMigrationPatterns()).hasSize(1);
    }

    @Test
    void defaultFlywayWouldStartOverAFuturePilotMigration() throws Exception {
        // A divergência apontada na revisão: com a omissão, V23 aplicada é «future» e é ignorada.
        String url = pilotDatabase();
        config(url, mainUpToV22, false).load().migrate();
    }

    @Test
    void strictValidationRefusesAFuturePilotMigration() throws Exception {
        String url = pilotDatabase();
        assertThatThrownBy(() -> config(url, mainUpToV22, true).load().migrate())
                .hasMessageContaining("23");
    }

    @Test
    void strictValidationRefusesAMissingPilotMigrationOnceV25Exists() throws Exception {
        String url = pilotDatabase();
        assertThatThrownBy(() -> config(url, mainWithV25, true).load().migrate())
                .hasMessageContaining("23");
    }

    @Test
    void strictValidationStillAppliesPendingMigrations() throws Exception {
        write(mainUpToV22, "V20__base.sql", "create table t20 (id int);");
        write(mainWithV25, "V20__base.sql", "create table t20 (id int);");
        write(mainWithV25, "V22__entailment.sql", "create table t22 (id int);");
        write(mainWithV25, "V25__ask_tickets.sql", "create table t25 (id int);");
        String url = newDatabase();
        config(url, mainUpToV22, true).load().migrate();
        assertThat(config(url, mainWithV25, true).load().migrate().migrationsExecuted).isEqualTo(2);
        config(url, mainWithV25, true).load().validate();
    }

    @Test
    void springBootAppliesTheCustomizerAndRefusesToStart() throws Exception {
        String url = pilotDatabase();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(StrictFlywayValidation.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "spring.flyway.locations=filesystem:" + mainUpToV22)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("23");
                });
        // Excepção expressa e humana: com a propriedade definida, o valor configurado prevalece e o arranque é aceite.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(StrictFlywayValidation.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "spring.flyway.locations=filesystem:" + mainUpToV22,
                        "spring.flyway.ignore-migration-patterns=*:future")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        // Controlo: sem o customizador, o mesmo arranque é aceite.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "spring.flyway.locations=filesystem:" + mainUpToV22)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
