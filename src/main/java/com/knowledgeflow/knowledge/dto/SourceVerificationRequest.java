package com.knowledgeflow.knowledge.dto;

/**
 * Verificação humana estruturada de uma fonte (C8, ADR-008). A data e o responsável vêm do servidor
 * (relógio e utilizador autenticado), nunca do pedido. {@code consolidatedVersionConfirmed}: só para
 * legislação — confirma que foi verificada a versão oficial/consolidada aplicável.
 */
public record SourceVerificationRequest(boolean consolidatedVersionConfirmed) {
}
