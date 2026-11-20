package com.knowledgeflow.research.integration;

import com.knowledgeflow.research.source.ResearchProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Guarda de arranque do modo integrado (ADR-010-A; especificação KF-CONV-01 v13, §3.1 e §3.5).
 * <p>
 * Com {@code knowledgeflow.research.ask-integration.enabled=true}, o arranque falha (fail closed) se faltar algum
 * pré-requisito. Nesta base faltam sempre dois, que dependem de decisão ou de integração ainda não feitas:
 * <ul>
 *   <li>o controlo de despesa das chamadas ao modelo do circuito integrado, com autoridade e envelope próprios
 *       (L6, decisão reservada à Administração; o envelope da R-4 está vedado);</li>
 *   <li>a segunda barreira de validação de implicação, obrigatória no modo integrado (§3.5), que existe só no ramo
 *       experimental {@code ac84c04}.</li>
 * </ul>
 * Por isso o modo integrado <b>não pode ser ligado</b> nesta base. Cada pré-requisito só sai desta lista no
 * incremento que o implementar, com revisão.
 */
@Component
public class AskIntegrationGuard implements InitializingBean {

    /** Pré-requisitos ainda ausentes nesta base. */
    static final List<String> MISSING_PREREQUISITES = List.of(
            "controlo de despesa das chamadas ao modelo do circuito integrado (L6: autoridade e envelope próprios, "
                    + "decisão reservada à Administração)",
            "segunda barreira de validação de implicação, obrigatória no modo integrado (ainda não integrada)");

    private final ResearchProperties properties;

    public AskIntegrationGuard(ResearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Modo integrado da investigação recusado no arranque: "
                    + String.join("; ", problems));
        }
    }

    /** Problemas que impedem o arranque; vazia quando o modo integrado está desligado. */
    static List<String> problems(ResearchProperties properties) {
        if (!properties.getAskIntegration().isEnabled()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        if (!properties.isEnabled()) {
            problems.add("knowledgeflow.research.enabled tem de estar ligado");
        }
        MISSING_PREREQUISITES.forEach(p -> problems.add("pré-requisito em falta: " + p));
        return List.copyOf(problems);
    }
}
