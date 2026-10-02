package com.knowledgeflow.knowledge.governance;

/**
 * Natureza de uma alteração a uma Q&amp;A (ADR-005). Numa versão publicada, MATERIAL e EXPANSIVE
 * exigem nova versão; numa versão VALIDATED não publicada, devolvem-na a revisão.
 */
public enum CurationChangeKind {
    /** Muda o conteúdo servido, o título, o âmbito derivado ou a jurisdição. */
    MATERIAL,
    /** Alarga suporte, aplicabilidade ou relaxa exigências de governação. */
    EXPANSIVE,
    /** Restringe (mais risco, mais exigência, menos validade): pode ser imediata. */
    CONSERVATIVE,
    /** Editorial interno, sem efeito no RAG, no âmbito, no suporte, no risco ou na validade. */
    FREE;

    public boolean requiresRevalidation() {
        return this == MATERIAL || this == EXPANSIVE;
    }
}
