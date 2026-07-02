package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fixtures controladas por tipo de afirmação sensível.
 * Verifica suporte e rejeição com contextos explícitos e mínimos.
 */
class GroundingClaimFixtureTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private AnswerGroundingValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AnswerGroundingValidator(PROPS_REJECT);
    }

    // ── TAXA ─────────────────────────────────────────────────────────────────

    @Test
    void taxa_inContext_supported() {
        var ctx = ctx("IVA", "Taxa?", "A taxa normal de IVA aplicável é 23%.");
        var result = validator.validate("A taxa normal de IVA é 23%.", ctx);

        assertThat(claim(result, SensitiveClaimType.TAX_RATE)).allMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void taxa_valorDiferente_unsupported() {
        var ctx = ctx("IVA", "Taxa?", "A taxa normal de IVA aplicável é 23%.");
        var result = validator.validate("A taxa aplicável é 17%.", ctx);

        assertThat(claim(result, SensitiveClaimType.TAX_RATE)).anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    @Test
    void taxa_repeticaoSuportada_naoFlagadaComoUnsupported() {
        var ctx = ctx("IVA", "Taxa?", "A taxa normal de IVA aplicável é 23%.");
        // Mesma taxa duas vezes na resposta → deduplicada → apenas 1 claim, suportada
        var result = validator.validate("A taxa é 23%. Confirmo: a taxa é 23%.", ctx);

        assertThat(claim(result, SensitiveClaimType.TAX_RATE)).allMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).isFalse();
    }

    // ── ARTIGO LEGAL ─────────────────────────────────────────────────────────

    @Test
    void artigo_inContext_supported() {
        var ctx = ctx("CIVA", "Isenção?", "Artigo 9.º do CIVA: a operação descrita está isenta.");
        var result = validator.validate("Nos termos do artigo 9.º do CIVA, a operação está isenta.", ctx);

        assertThat(claim(result, SensitiveClaimType.LEGAL_REFERENCE))
                .anyMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void artigo_diferente_notInContext_rejected() {
        var ctx = ctx("CIVA", "Isenção?", "Artigo 9.º do CIVA: a operação está isenta.");
        // Answer references artigo 21.º — not in context
        var result = validator.validate("Nos termos do artigo 21.º do CIVA, a dedução está limitada.", ctx);

        assertThat(claim(result, SensitiveClaimType.LEGAL_REFERENCE))
                .anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    // ── PRAZO ─────────────────────────────────────────────────────────────────

    @Test
    void prazo_inContext_supported() {
        var ctx = ctx("IRS", "Prazo?", "O prazo aplicável é de 30 dias.");
        var result = validator.validate("O prazo é de 30 dias para apresentar reclamação.", ctx);

        assertThat(claim(result, SensitiveClaimType.DEADLINE)).anyMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void prazo_diferente_rejected() {
        var ctx = ctx("IRS", "Prazo?", "O prazo aplicável é de 30 dias.");
        var result = validator.validate("O prazo é de 60 dias.", ctx);

        assertThat(claim(result, SensitiveClaimType.DEADLINE)).anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    // ── OBRIGAÇÃO LEGAL ───────────────────────────────────────────────────────

    @Test
    void obrigacao_inContext_supported() {
        var ctx = ctx("IVA", "Registar?", "O registo para efeitos de IVA é obrigatório quando o volume excede o limiar.");
        var result = validator.validate("O registo para efeitos de IVA é obrigatório.", ctx);

        assertThat(claim(result, SensitiveClaimType.LEGAL_OBLIGATION)).anyMatch(SensitiveClaim::supported);
    }

    @Test
    void obrigacao_ausente_unsupportedAndRejected() {
        var ctx = ctx("IVA", "Regime?", "O regime de IVA aplica-se aos sujeitos passivos.");
        var result = validator.validate("A empresa é obrigatória a entregar a declaração mensal.", ctx);

        assertThat(claim(result, SensitiveClaimType.LEGAL_OBLIGATION)).anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    // ── ISENÇÃO ───────────────────────────────────────────────────────────────

    @Test
    void isencao_inContext_supported() {
        var ctx = ctx("CIVA Isenções", "Isento?", "A actividade médica está isenta de IVA ao abrigo do artigo 9.º.");
        var result = validator.validate("A actividade médica está isenta de IVA.", ctx);

        assertThat(claim(result, SensitiveClaimType.EXEMPTION)).anyMatch(SensitiveClaim::supported);
    }

    @Test
    void isencao_ausente_unsupportedAndRejected() {
        var ctx = ctx("CIVA", "Isenção?", "As actividades liberais têm tratamento específico em IVA.");
        var result = validator.validate("Esta actividade está isenta de IVA.", ctx);

        assertThat(claim(result, SensitiveClaimType.EXEMPTION)).anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    // ── DEDUTIBILIDADE ────────────────────────────────────────────────────────

    @Test
    void dedutibilidade_naoEDedutivel_inContext_supported() {
        var ctx = ctx("IVA Despesas", "Dedutível?", "O IVA suportado nesta situação não é dedutível.");
        var result = validator.validate("O IVA das despesas de representação não é dedutível.", ctx);

        assertThat(claim(result, SensitiveClaimType.DEDUCTIBILITY)).anyMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void dedutibilidade_ausente_unsupportedAndRejected() {
        var ctx = ctx("IVA", "Dedução?", "As despesas de representação têm regime especial.");
        var result = validator.validate("O IVA das despesas de representação não é dedutível.", ctx);

        assertThat(claim(result, SensitiveClaimType.DEDUCTIBILITY)).anyMatch(c -> !c.supported());
        assertThat(result.rejected()).isTrue();
    }

    @Test
    void dedutibilidade_eDedutivel_ausente_rejected() {
        var ctx = ctx("IVA", "Dedução?", "A dedução depende do uso em operações tributáveis.");
        var result = validator.validate("O IVA suportado nesta situação é dedutível.", ctx);

        assertThat(result.rejected()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<RetrievedCase> ctx(String title, String question, String content) {
        return List.of(new RetrievedCase(title, question, content, 0.9));
    }

    private List<SensitiveClaim> claim(GroundingValidationResult r, SensitiveClaimType type) {
        return r.allClaims().stream().filter(c -> c.type() == type).toList();
    }
}
