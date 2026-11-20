package com.knowledgeflow.ai.spend;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;

/**
 * Porta das chamadas ao modelo do percurso de investigação (especificação KF-CONV-01 v13, §3.9). O pacote
 * {@code research} usa exclusivamente esta porta; nunca o {@code AIService} genérico, que repete tentativas.
 * <p>
 * <b>Comportamento implementado ({@code main}).</b> A única implementação é {@link UnfundedModelClient}: uma única
 * tentativa, só para fornecedores não facturáveis (o {@code stub}). Qualquer fornecedor facturável, ou cuja facturação
 * se desconheça, é recusado com {@link SpendRefusedException} <em>antes</em> do envio. Não há autoridade financeira,
 * envelope, reserva, liquidação, contabilização nem preços: não existe nenhuma chamada paga por esta porta.
 * <p>
 * <b>Contrato financeiro futuro (L6, reservado à Administração; ainda não implementado).</b> Quando houver decisão sobre a
 * autoridade e o envelope do circuito, uma implementação diferente desta porta deverá, para cada chamada facturável:
 * verificar a identidade da autoridade; reservar antes do envio; fazer uma única tentativa; liquidar com os tokens
 * reportados, ou cobrar o valor reservado em caso de falha; e recusar sem enviar em caso de indisponibilidade. Este
 * parágrafo descreve uma exigência da especificação, não um comportamento existente.
 */
public interface MeteredModelClient {

    /**
     * Uma única tentativa ao modelo.
     * <p>
     * Implementação actual: só fornecedores não facturáveis; os facturáveis são recusados sem envio. (Futuro L6: reserva
     * antes e liquidação depois, por cada tentativa facturável.)
     *
     * @throws SpendRefusedException se a chamada for recusada; o pedido não é enviado ao fornecedor
     */
    AIResponse complete(AIRequest request);
}
