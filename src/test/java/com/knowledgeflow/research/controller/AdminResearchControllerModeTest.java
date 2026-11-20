package com.knowledgeflow.research.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.research.service.CriticalMemoryService;
import com.knowledgeflow.research.service.InvestigationService;
import com.knowledgeflow.research.service.InvestigationTraceService;
import com.knowledgeflow.research.source.ResearchProperties;
import com.knowledgeflow.security.AuthenticatedUserContext;
import org.junit.jupiter.api.Test;

/** Modos exclusivos (§3.1): com o modo integrado ligado, a criação directa é recusada antes de qualquer efeito. */
class AdminResearchControllerModeTest {

    @Test
    void directCreationIsForbiddenWhenIntegratedModeIsOn() {
        InvestigationService service = mock(InvestigationService.class);
        AuthenticatedUserContext auth = mock(AuthenticatedUserContext.class);
        ResearchProperties props = new ResearchProperties();
        props.setEnabled(true);
        props.getAskIntegration().setEnabled(true);
        AdminResearchController controller = new AdminResearchController(service,
                mock(InvestigationTraceService.class), mock(CriticalMemoryService.class), auth, props);

        assertThatThrownBy(() -> controller.investigate(
                new AdminResearchController.InvestigationRequest("Pergunta qualquer")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.FORBIDDEN));
        verifyNoInteractions(service, auth);
    }
}
