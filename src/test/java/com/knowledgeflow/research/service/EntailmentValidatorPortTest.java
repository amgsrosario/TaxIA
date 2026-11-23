package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIProviderResolver;
import com.knowledgeflow.ai.spend.UnfundedModelClient;
import com.knowledgeflow.research.service.EntailmentValidator.Request;
import com.knowledgeflow.research.source.ResearchProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-010-A, A2: o validador de implicação só fala com o modelo pela porta {@code MeteredModelClient}. Sem L6, a
 * implementação {@link UnfundedModelClient} recusa fornecedores facturáveis antes do envio, e o validador devolve uma
 * falha de protocolo; a decisão é sempre «não demonstrada». Nenhuma chamada paga é possível.
 */
class EntailmentValidatorPortTest {

    private static final Request REQUEST = new Request("pergunta", "proposição", List.of(), "CIRS 78.º",
            "unidade legal", "texto do artigo");

    private static AIProvider billableProvider() {
        AIProvider p = mock(AIProvider.class);
        when(p.providerId()).thenReturn("pago");
        when(p.supportsSingleAttempt()).thenReturn(true);
        when(p.billable()).thenReturn(true);
        return p;
    }

    private static ModelEntailmentValidator validator(AIProvider provider, boolean enabled) {
        AIProviderResolver resolver = mock(AIProviderResolver.class);
        when(resolver.resolve()).thenReturn(provider);
        ResearchProperties props = new ResearchProperties();
        props.getEntailment().setEnabled(enabled);
        return new ModelEntailmentValidator(new UnfundedModelClient(resolver), new ObjectMapper(),
                new ModelIdentity(mock(AIProperties.class)), props);
    }

    @Test
    void disabledByDefaultAndNeverCallsTheModel() {
        assertThat(new ResearchProperties().getEntailment().isEnabled()).isFalse();
        AIProvider provider = billableProvider();
        ModelEntailmentValidator v = validator(provider, false);
        assertThat(v.isAvailable()).isFalse();
        EntailmentValidator.Assessment a = v.assess(REQUEST);
        assertThat(a.protocolError()).isEqualTo("validador desligado");
        assertThat(a.verdict()).isNull();
        verify(provider, never()).generate(any());
        verify(provider, never()).generateSingleAttempt(any());
    }

    @Test
    void enabledWithBillableProviderIsRefusedBeforeSendingAndNotDemonstrated() {
        AIProvider provider = billableProvider();
        EntailmentValidator.Assessment a = validator(provider, true).assess(REQUEST);
        assertThat(a.protocolError()).isEqualTo("falha da chamada ao modelo");
        assertThat(a.verdict()).isNull();
        assertThat(a.call()).isNotNull();
        verify(provider, never()).generate(any());
        verify(provider, never()).generateSingleAttempt(any());
        assertThat(new EntailmentGate().decide(a, "proposição", "texto do artigo", "unidade legal").demonstrated()).isFalse();
    }

    @Test
    void effectiveMaxChecksFailsSafe() {
        ResearchProperties.Entailment e = new ResearchProperties.Entailment();
        assertThat(e.effectiveMaxChecks()).isEqualTo(4);
        e.setMaxChecks(10);
        assertThat(e.effectiveMaxChecks()).isEqualTo(ResearchProperties.Entailment.KF_M02_MAX_CHECKS_LIMIT);
        e.setMaxChecks(0);
        assertThat(e.effectiveMaxChecks()).isZero();
        e.setMaxChecks(-3);
        assertThat(e.effectiveMaxChecks()).isZero();
    }
}
