package com.knowledgeflow.ai.grounding;

import com.knowledgeflow.ai.AIResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SafeResponseFactory {

    private static final String REFUSAL_ANSWER =
            "Não foi possível encontrar documentação validada suficiente para responder a esta questão "
            + "de forma fundamentada. Para uma resposta rigorosa e segura, consulte um profissional "
            + "fiscal qualificado ou aguarde que a base de conhecimento seja enriquecida com pareceres "
            + "relevantes sobre este tema.";

    private static final String REJECTED_ANSWER =
            "A resposta gerada contém afirmações fiscais sensíveis (taxas, artigos, prazos ou valores) "
            + "sem suporte documental verificável no contexto disponível. Por segurança, a resposta foi "
            + "bloqueada. Consulte um profissional fiscal qualificado.";

    public GroundedAIResponse buildRefusal(ContextSufficiencyAssessment assessment) {
        boolean humanReview = assessment.humanReviewRequired();
        String validationMsg = humanReview
                ? "Esta matéria envolve risco elevado e requer validação por profissional fiscal qualificado."
                : null;
        return new GroundedAIResponse(
                REFUSAL_ANSWER,
                AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                assessment.reason(),
                List.of(),
                assessment.missingElements(),
                List.of("Resposta limitada por ausência de documentação validada."),
                humanReview,
                validationMsg,
                false,
                false,
                null,
                0,
                "none",
                "none",
                0,
                0,
                0L);
    }

    public GroundedAIResponse buildRejected(
            AIResponse aiResponse,
            ContextSufficiencyAssessment assessment,
            GroundingValidationResult validation) {
        return new GroundedAIResponse(
                REJECTED_ANSWER,
                AnswerSupportStatus.REJECTED_UNSUPPORTED,
                validation.rejectionReason(),
                List.of(),
                assessment.missingElements(),
                List.of("Resposta gerada bloqueada por conter afirmações sem fundamento documental."),
                true,
                "Resposta rejeitada pelo validador de fundamentação. Requer revisão humana.",
                true,
                true,
                validation.rejectionReason(),
                validation.unsupportedClaims().size(),
                aiResponse.provider(),
                aiResponse.modelUsed(),
                aiResponse.inputTokens(),
                aiResponse.outputTokens(),
                aiResponse.durationMillis());
    }
}
