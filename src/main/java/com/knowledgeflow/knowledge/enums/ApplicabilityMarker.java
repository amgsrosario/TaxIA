package com.knowledgeflow.knowledge.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Vocabulário fechado de exclusões de aplicabilidade (M4-SCOPE-V2, ADR-004): situações que uma Q&amp;A
 * pode declarar que NÃO cobre. O código é estável e é o que fica persistido; o rótulo é para o
 * backoffice. A detecção na pergunta vive no gate ({@code ApplicabilityMarkerDetector}).
 *
 * <p>Só entram marcadores com falsos positivos observados e relevância editorial. Acrescentar um
 * marcador não exige migração (a BD não tem CHECK), mas exige detector, testes e held-out.
 */
public enum ApplicabilityMarker {
    INQUILINO("Inquilino / arrendatário"),
    HABITACAO_PROPRIA("Habitação própria"),
    HERANCA_INDIVISA("Herança indivisa"),
    PENSAO_ESTRANGEIRA("Pensão estrangeira"),
    CALCULO("Cálculo do imposto"),
    RECLAMACAO("Reclamação / impugnação"),
    PAGAMENTO("Pagamento / prestações"),
    RETENCAO_OBRIGATORIA("Retenção obrigatória (dever de reter)");

    private final String label;

    ApplicabilityMarker(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Código persistido → marcador; vazio para códigos desconhecidos (dados inválidos). */
    public static Optional<ApplicabilityMarker> fromCode(String code) {
        return Arrays.stream(values()).filter(m -> m.name().equals(code)).findFirst();
    }
}
