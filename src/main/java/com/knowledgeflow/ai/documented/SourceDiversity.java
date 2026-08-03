package com.knowledgeflow.ai.documented;

/**
 * Diversidade material real do conjunto de fontes usadas (Decisão C9).
 *
 * <p>A robustez aumenta quando as fontes acrescentam fundamentos distintos, não quando
 * multiplicam o mesmo núcleo material. Valores conceptuais, sem thresholds nesta fase.
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum SourceDiversity {

    /** As fontes acrescentam fundamentos materialmente distintos. */
    MATERIAL_DIVERSITY,

    /** As fontes partilham o mesmo núcleo material (eco documental). */
    SAME_CORE,

    /** Diversidade mista ou indeterminada. */
    MIXED_OR_UNCLEAR
}
