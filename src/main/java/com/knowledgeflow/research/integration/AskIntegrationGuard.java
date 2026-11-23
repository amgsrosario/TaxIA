package com.knowledgeflow.research.integration;

import com.knowledgeflow.research.source.ResearchProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Guarda de arranque do modo integrado (ADR-010-A; especificação KF-CONV-01 v13, §3.1 e §3.5).
 * <p>
 * Com {@code knowledgeflow.research.ask-integration.enabled=true}, o arranque falha (fail closed) se faltar algum
 * pré-requisito. Nesta base faltam sempre dois, que dependem de decisão:
 * <ul>
 *   <li>o controlo de despesa das chamadas ao modelo do circuito integrado, com autoridade e envelope próprios
 *       (L6, reservado; o envelope da R-4 está vedado);</li>
 *   <li>a composição restrita da resposta, pré-requisito de qualquer activação do modo integrado (deliberação de
 *       23/11/2026, ponto 2): com o compositor actual, uma proposição rejeitada pela barreira pode reaparecer a
 *       partir da evidência partilhada (delimitação v3, A.4).</li>
 * </ul>
 * A segunda barreira de validação de implicação existe desde o A4 e é <b>exigida</b>: com o modo integrado ligado,
 * {@code knowledgeflow.research.entailment.enabled} tem de estar ligado (§3.5).
 * Por isso o modo integrado <b>não pode ser ligado</b> nesta base. Cada pré-requisito só sai desta lista no
 * incremento que o implementar, com revisão. Quando os cenários (B5) forem integrados, a guarda passará também a
 * exigir {@code scenarios.enabled=false} no modo integrado (§3.5).
 * <p>
 * {@code @Lazy(false)}: a guarda é sempre criada no arranque, também com
 * {@code spring.main.lazy-initialization=true}, que de outro modo a podia adiar indefinidamente.
 */
@Component
@Lazy(false)
public class AskIntegrationGuard implements InitializingBean {

    /** Pré-requisitos ainda ausentes nesta base. */
    static final List<String> MISSING_PREREQUISITES = List.of(
            "controlo de despesa das chamadas ao modelo do circuito integrado (L6: autoridade e envelope próprios, "
                    + "decisão reservada à Administração)",
            "composição restrita da resposta (o texto livre do modelo não pode reintroduzir proposições rejeitadas "
                    + "pela barreira), pré-requisito de activação fixado pela Administração");

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
        if (!properties.getEntailment().isEnabled()) {
            problems.add("knowledgeflow.research.entailment.enabled tem de estar ligado (barreira de implicação obrigatória)");
        }
        MISSING_PREREQUISITES.forEach(p -> problems.add("pré-requisito em falta: " + p));
        return List.copyOf(problems);
    }
}
