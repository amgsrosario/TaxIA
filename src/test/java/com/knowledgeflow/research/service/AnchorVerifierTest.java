package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnchorVerifierTest {

    private static final String EVIDENCE = """
            Deduções à coleta
            5 - As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português.
            7 - O limite é de 1 000 euros por agregado.""";

    private final AnchorVerifier verifier = new AnchorVerifier();

    @Test
    @DisplayName("Âncora literal é aceite, com espaços, maiúsculas e caracteres invisíveis normalizados")
    void literalAnchorAccepted() {
        assertThat(verifier.isAnchored("aplicam-se apenas aos sujeitos passivos residentes em território português",
                EVIDENCE)).isTrue();
        assertThat(verifier.isAnchored("APLICAM-SE  apenas​ aos sujeitos passivos residentes", EVIDENCE)).isTrue();
    }

    @Test
    @DisplayName("Paráfrase, âncora curta ou ausente é recusada")
    void paraphraseRejected() {
        assertThat(verifier.isAnchored("só se aplicam a residentes em Portugal", EVIDENCE)).isFalse();
        assertThat(verifier.isAnchored("residentes", EVIDENCE)).isFalse();
        assertThat(verifier.isAnchored(null, EVIDENCE)).isFalse();
        assertThat(verifier.isAnchored("", EVIDENCE)).isFalse();
    }

    @Test
    @DisplayName("Números da afirmação têm de constar da evidência (5 ≠ 15, 1 000 ≠ 2 000)")
    void numbersMustBeInEvidence() {
        assertThat(verifier.numbersSupported("Nos termos do n.º 5, só residentes deduzem.", EVIDENCE)).isTrue();
        assertThat(verifier.numbersSupported("Nos termos do n.º 15, só residentes deduzem.", EVIDENCE)).isFalse();
        assertThat(verifier.numbersSupported("O limite é de 2 000 euros.", EVIDENCE)).isFalse();
        assertThat(verifier.numbersSupported("Sem números.", EVIDENCE)).isTrue();
    }
}
