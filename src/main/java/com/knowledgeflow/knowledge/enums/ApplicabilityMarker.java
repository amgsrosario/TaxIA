package com.knowledgeflow.knowledge.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Vocabulário fechado de aplicabilidade (M4-SCOPE-V2, ADR-004; ADR-009): situações que uma Q&amp;A
 * pode declarar que NÃO cobre (exclusão) ou que exige na pergunta (contexto obrigatório). O código é
 * estável e é o que fica persistido; o rótulo é para o
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
    RETENCAO_OBRIGATORIA("Retenção obrigatória (dever de reter)"),
    // C9-A (ADR-009): casos observados no piloto em 2026-10-14
    SUBLOCACAO("Sublocação / subarrendamento", true),
    ACTO_ISOLADO("Acto isolado", true),
    FORNECEDOR_NAO_RESIDENTE("Fornecedor / prestador não residente", true),
    ADQUIRENTE_NAO_RESIDENTE("Adquirente / cliente não residente", true);

    private final String label;
    /**
     * Pode ser usado como contexto obrigatório (ADR-009). Só marcadores com detecção afinada para
     * precisão (papéis, negações) e testes negativos próprios: num contexto obrigatório um falso
     * positivo aceita o caso oposto. Os restantes foram afinados para exclusão (evitar rejeições).
     */
    private final boolean requirable;

    ApplicabilityMarker(String label) {
        this(label, false);
    }

    ApplicabilityMarker(String label, boolean requirable) {
        this.label = label;
        this.requirable = requirable;
    }

    public boolean requirable() {
        return requirable;
    }

    public String label() {
        return label;
    }

    /** Código persistido → marcador; vazio para códigos desconhecidos (dados inválidos). */
    public static Optional<ApplicabilityMarker> fromCode(String code) {
        return Arrays.stream(values()).filter(m -> m.name().equals(code)).findFirst();
    }
}
