package com.knowledgeflow.config.flyway;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * Validação Flyway estrita (ADR-010-A; ver {@code docs/flyway-numeracao-adr010a.md}).
 * <p>
 * Por omissão, o Flyway ignora as migrações aplicadas na base que são mais recentes do que todas as locais
 * ({@code ignoreMigrationPatterns = "*:future"}). Uma base em que se tenham aplicado as V23 ou V24 do ramo do piloto
 * (autoridade financeira experimental e cenários, não importadas) arrancaria então sem aviso enquanto o {@code main}
 * acabasse em V22 ou antes.
 * <p>
 * Aqui não se ignora nenhum tipo de migração: qualquer migração aplicada que não exista localmente, futura ou em
 * falta, faz falhar a validação e, portanto, o arranque. Aceitar uma base nessas condições é uma decisão humana
 * (reposição da cópia de segurança, ou configuração expressa), nunca da aplicação. As migrações pendentes
 * continuam a ser aplicadas normalmente.
 */
@Component
public class StrictFlywayValidation implements FlywayConfigurationCustomizer {

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.ignoreMigrationPatterns(new String[0]);
    }
}
