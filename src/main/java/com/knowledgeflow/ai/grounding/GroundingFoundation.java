package com.knowledgeflow.ai.grounding;

import java.util.UUID;

/**
 * Fundamento efectivamente usado na resposta (M4B, ADR-007): um candidato que passou o threshold
 * de relevância e o gate de âmbito/aplicabilidade e foi enviado ao provider como contexto.
 * Candidatos descartados nunca são fundamentos.
 *
 * <p>Ao contrário de {@link GroundedAIResponse#sources()}, a lista de fundamentos não é deduplicada
 * por título: duas Q&amp;A distintas com o mesmo título contam ambas para o risco agregado.
 *
 * @param sourceQaId id da Q&amp;A de origem; {@code null} para casos {@code DOCUMENT} (sem metadados
 *     governados de risco — tratados como risco desconhecido)
 * @param title título do caso usado (rastreabilidade)
 */
public record GroundingFoundation(UUID sourceQaId, String title) {
}
