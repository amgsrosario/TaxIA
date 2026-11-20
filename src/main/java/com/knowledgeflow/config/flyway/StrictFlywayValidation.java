package com.knowledgeflow.config.flyway;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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
 * falta, faz falhar a validação e, portanto, o arranque. As migrações pendentes continuam a ser aplicadas.
 * <p>
 * Aceitar uma base nessas condições é uma decisão humana, nunca da aplicação: repor a cópia de segurança, ou definir
 * expressamente {@value #PROPERTY}. Com a propriedade definida, o valor configurado prevalece e este componente não o
 * altera. O padrão tem de cobrir o estado das versões do piloto: {@code *:future} enquanto a versão local mais recente
 * lhes for inferior, {@code *:missing} quando for superior (por exemplo, V25), ou {@code *:future,*:missing}.
 */
@Component
public class StrictFlywayValidation implements FlywayConfigurationCustomizer {

    static final String PROPERTY = "spring.flyway.ignore-migration-patterns";

    private final Environment environment;

    public StrictFlywayValidation(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (explicitlyConfigured()) return;
        configuration.ignoreMigrationPatterns(new String[0]);
    }

    private boolean explicitlyConfigured() {
        return Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).isBound();
    }
}
