package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerGroundingValidatorTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);
    private static final GroundingProperties PROPS_PERMISSIVE =
            new GroundingProperties(true, 1, 1, 0.0, false, true);

    private AnswerGroundingValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AnswerGroundingValidator(PROPS_REJECT);
    }

    // --- Tax rates ---

    @Test
    void answerWithRateInContext_claimIsSupported() {
        var context = List.of(case_("IVA", "Qual a taxa?", "A taxa normal de IVA é 23%."));
        var result = validator.validate("A taxa aplicável é de 23%.", context);

        assertThat(result.rejected()).isFalse();
        assertThat(result.allClaims()).hasSize(1);
        assertThat(result.allClaims().get(0).supported()).isTrue();
        assertThat(result.allClaims().get(0).type()).isEqualTo(SensitiveClaimType.TAX_RATE);
    }

    @Test
    void answerWithRateNotInContext_claimIsUnsupported() {
        var context = List.of(case_("IVA", "Qual a taxa?", "A taxa normal de IVA é 13%."));
        var result = validator.validate("A taxa aplicável é de 23%.", context);

        assertThat(result.allClaims()).hasSize(1);
        assertThat(result.allClaims().get(0).supported()).isFalse();
    }

    @Test
    void answerWithRateNotInContext_rejectEnabled_resultIsRejected() {
        var context = List.of(case_("IVA", "Qual a taxa?", "A taxa intermédia é de 13%."));
        var result = validator.validate("A taxa de IVA é 23%.", context);

        assertThat(result.rejected()).isTrue();
        assertThat(result.unsupportedClaims()).hasSize(1);
        assertThat(result.rejectionReason()).contains("23%");
    }

    @Test
    void answerWithRateNotInContext_rejectDisabled_notRejected() {
        var permissive = new AnswerGroundingValidator(PROPS_PERMISSIVE);
        var context = List.of(case_("IVA", "Qual a taxa?", "Taxa intermédia: 13%."));
        var result = permissive.validate("A taxa de IVA é 23%.", context);

        assertThat(result.rejected()).isFalse();
        assertThat(result.unsupportedClaims()).hasSize(1);
    }

    // --- Legal references ---

    @Test
    void answerWithArticleInContext_claimIsSupported() {
        var context = List.of(case_("CIVA", "Isenções?", "Nos termos do artigo 9.º do CIVA estão isentos..."));
        var result = validator.validate("Conforme o artigo 9.º do CIVA, há isenção.", context);

        assertThat(result.rejected()).isFalse();
        assertThat(result.allClaims()).isNotEmpty();
        boolean articleClaimSupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE)
                .anyMatch(SensitiveClaim::supported);
        assertThat(articleClaimSupported).isTrue();
    }

    @Test
    void answerWithArticleNotInContext_claimIsUnsupported() {
        var context = List.of(case_("IRC", "Taxa IRC?", "A taxa de IRC é 21%."));
        var result = validator.validate("Conforme o artigo 87.º do CIRC, a taxa é 21%.", context);

        boolean articleUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE)
                .anyMatch(c -> !c.supported());
        assertThat(articleUnsupported).isTrue();
    }

    // --- Monetary values ---

    @Test
    void answerWithMonetaryValueInContext_claimIsSupported() {
        var context = List.of(case_("IVA limiar", "Quando IVA mensal?", "Volume de negócios superior a 650 000 € obriga a periodicidade mensal."));
        var result = validator.validate("Empresas com volume superior a 650 000 € devem declarar mensalmente.", context);

        assertThat(result.rejected()).isFalse();
    }

    @Test
    void answerWithMonetaryValueNotInContext_claimIsUnsupported() {
        var context = List.of(case_("IVA limiar", "Quando IVA mensal?", "Consulte a legislação aplicável."));
        var result = validator.validate("O limiar é de 650 000 €.", context);

        assertThat(result.unsupportedClaims()).isNotEmpty();
    }

    // --- Deadlines ---

    @Test
    void answerWithDeadlineInContext_claimIsSupported() {
        var context = List.of(case_("IRS", "Prazo?", "O contribuinte dispõe de 30 dias para apresentar reclamação."));
        var result = validator.validate("O prazo é de 30 dias.", context);

        assertThat(result.rejected()).isFalse();
    }

    @Test
    void answerWithDeadlineNotInContext_claimIsUnsupported() {
        var context = List.of(case_("IRS", "Prazo?", "Consulte os prazos previstos na lei."));
        var result = validator.validate("O prazo de reclamação é de 90 dias.", context);

        assertThat(result.unsupportedClaims()).isNotEmpty();
    }

    // --- Clean answers ---

    @Test
    void cleanAnswerWithNoSensitiveClaims_noClaimsDetected() {
        var context = List.of(case_("Geral", "Questão geral", "Informação geral sobre direito fiscal."));
        var result = validator.validate("Esta é uma resposta geral sem valores específicos.", context);

        assertThat(result.allClaims()).isEmpty();
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void emptyAnswer_noClaimsDetectedAndNotRejected() {
        var context = List.of(case_("Fonte", "Q", "Conteúdo."));
        var result = validator.validate("", context);

        assertThat(result.allClaims()).isEmpty();
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void nullAnswer_noClaimsDetectedAndNotRejected() {
        var context = List.of(case_("Fonte", "Q", "Conteúdo."));
        var result = validator.validate(null, context);

        assertThat(result.allClaims()).isEmpty();
        assertThat(result.rejected()).isFalse();
    }

    // --- Multiple claims ---

    @Test
    void multipleClaimsSomeSupported_partialResult() {
        // Context has 23% but not 90 dias
        var context = List.of(case_("IVA", "Qual a taxa?", "A taxa normal de IVA é 23%."));
        var result = validator.validate(
                "A taxa é 23% e o prazo é de 90 dias.", context);

        long supportedCount = result.allClaims().stream().filter(SensitiveClaim::supported).count();
        long unsupportedCount = result.allClaims().stream().filter(c -> !c.supported()).count();
        assertThat(supportedCount).isGreaterThanOrEqualTo(1);   // 23% presente no contexto
        assertThat(unsupportedCount).isGreaterThanOrEqualTo(1); // 90 dias não está no contexto
    }

    @Test
    void duplicateClaim_deduplicatedInResults() {
        var context = List.of(case_("IVA", "Taxa?", "Taxa de 23%."));
        var result = validator.validate("A taxa é 23%. Repito: a taxa é 23%.", context);

        long count23 = result.allClaims().stream()
                .filter(c -> c.text().contains("23"))
                .count();
        assertThat(count23).isEqualTo(1);
    }

    // --- LEGAL_OBLIGATION (text-based) ---

    @Test
    void obligation_eObrigatorio_notInContext_claimIsUnsupported() {
        var context = List.of(case_("IVA", "Quando registar?", "Os sujeitos passivos devem registar-se."));
        var result = validator.validate("A entrega da declaração é obrigatória para todos os contribuintes.", context);

        assertThat(result.allClaims()).isNotEmpty();
        boolean obligationUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_OBLIGATION)
                .anyMatch(c -> !c.supported());
        assertThat(obligationUnsupported).isTrue();
    }

    @Test
    void obligation_eObrigatorio_inContext_claimIsSupported() {
        var context = List.of(case_("IVA", "Obrigações?", "A entrega da declaração é obrigatória para todos os contribuintes."));
        var result = validator.validate("A entrega da declaração é obrigatória para todos os contribuintes.", context);

        boolean obligationSupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_OBLIGATION)
                .anyMatch(SensitiveClaim::supported);
        assertThat(obligationSupported).isTrue();
    }

    @Test
    void obligation_deveLiquidarIva_notInContext_claimIsUnsupported() {
        var context = List.of(case_("IVA", "Regime?", "O regime de IVA aplica-se a sujeitos passivos."));
        var result = validator.validate("A empresa deve liquidar IVA nas suas prestações de serviços.", context);

        boolean obligationUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_OBLIGATION)
                .anyMatch(c -> !c.supported());
        assertThat(obligationUnsupported).isTrue();
    }

    @Test
    void obligation_podeRenunciar_inContext_claimIsSupported() {
        var context = List.of(case_("IVA Isenções", "Renúncia?", "O sujeito passivo pode renunciar à isenção quando cumprir os requisitos."));
        var result = validator.validate("O sujeito passivo pode renunciar à isenção do artigo 9.º.", context);

        boolean obligationSupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_OBLIGATION)
                .anyMatch(SensitiveClaim::supported);
        assertThat(obligationSupported).isTrue();
    }

    // --- EXEMPTION (text-based) ---

    @Test
    void exemption_estaIsento_notInContext_claimIsUnsupported() {
        var context = List.of(case_("Médicos", "Regime?", "As prestações de serviços médicos têm tratamento especial em IVA."));
        var result = validator.validate("O médico está isento de IVA.", context);

        assertThat(result.allClaims()).isNotEmpty();
        boolean exemptionUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.EXEMPTION)
                .anyMatch(c -> !c.supported());
        assertThat(exemptionUnsupported).isTrue();
    }

    @Test
    void exemption_notInContext_rejectEnabled_resultIsRejected() {
        var context = List.of(case_("Médicos", "Regime?", "As prestações de serviços médicos têm tratamento especial em IVA."));
        var result = validator.validate("Esta actividade está isenta de IVA.", context);

        assertThat(result.rejected()).isTrue();
        assertThat(result.unsupportedClaims()).isNotEmpty();
    }

    @Test
    void exemption_naoEstaSujeita_notInContext_claimIsUnsupported() {
        var context = List.of(case_("Operações", "Sujeição?", "As operações financeiras têm tratamento específico em IVA."));
        var result = validator.validate("Esta operação não está sujeita a IVA.", context);

        boolean exemptionUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.EXEMPTION)
                .anyMatch(c -> !c.supported());
        assertThat(exemptionUnsupported).isTrue();
    }

    // --- DEDUCTIBILITY (text-based) ---

    @Test
    void deductibility_eDedutivel_notInContext_claimIsUnsupported() {
        var context = List.of(case_("Despesas", "Dedução?", "As despesas de representação têm regime especial."));
        var result = validator.validate("O IVA das despesas de representação é dedutível.", context);

        assertThat(result.allClaims()).isNotEmpty();
        boolean deductUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.DEDUCTIBILITY)
                .anyMatch(c -> !c.supported());
        assertThat(deductUnsupported).isTrue();
    }

    @Test
    void deductibility_naoEDedutivel_notInContext_claimIsUnsupported() {
        var context = List.of(case_("Refeições", "Dedução?", "As despesas com refeições têm limitações em IVA."));
        var result = validator.validate("O IVA das refeições não é dedutível.", context);

        boolean deductUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.DEDUCTIBILITY)
                .anyMatch(c -> !c.supported());
        assertThat(deductUnsupported).isTrue();
    }

    @Test
    void deductibility_temDireitoADeducao_notInContext_claimIsUnsupported() {
        var context = List.of(case_("IVA", "Dedução?", "A dedução de IVA está condicionada ao uso em operações tributáveis."));
        var result = validator.validate("A empresa tem direito à dedução do IVA suportado nas aquisições.", context);

        boolean deductUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.DEDUCTIBILITY)
                .anyMatch(c -> !c.supported());
        assertThat(deductUnsupported).isTrue();
    }

    @Test
    void deductibility_podeDeduzir_inContext_claimIsSupported() {
        var context = List.of(case_("IVA", "Dedução?", "O sujeito passivo pode deduzir o IVA das aquisições para a actividade."));
        var result = validator.validate("A empresa pode deduzir o IVA suportado.", context);

        boolean deductSupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.DEDUCTIBILITY)
                .anyMatch(SensitiveClaim::supported);
        assertThat(deductSupported).isTrue();
    }

    // --- helper ---

    private RetrievedCase case_(String title, String question, String content) {
        return new RetrievedCase(title, question, content, 0.9);
    }
}
