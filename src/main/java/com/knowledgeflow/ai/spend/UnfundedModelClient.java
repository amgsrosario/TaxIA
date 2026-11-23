package com.knowledgeflow.ai.spend;

import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIProviderResolver;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import org.springframework.stereotype.Service;

/**
 * Implementação da porta {@link MeteredModelClient} sem autoridade financeira (ADR-010-A; especificação KF-CONV-01
 * v13, §3.9).
 * <p>
 * O {@code main} não tem controlo de despesa: a autoridade e o envelope do circuito de investigação (L6) são decisão
 * reservada da Administração, e o envelope experimental da R-4 não serve este circuito. Até existirem, nenhuma
 * chamada facturável é enviada:
 * <ol>
 *   <li>fornecedor sem chamada de tentativa única: recusa, sem envio;</li>
 *   <li>fornecedor não facturável (stub, sem chamada externa): uma única tentativa, sem custo;</li>
 *   <li>fornecedor facturável, ou cuja facturação se desconhece (a omissão de {@link AIProvider#billable()} é
 *       {@code true}): recusa com {@link SpendRefusedException}, sem envio.</li>
 * </ol>
 * Nunca usa o {@code AIService} genérico, que repete tentativas. A implementação com reserva e liquidação substitui
 * esta quando houver decisão sobre o L6; não é importada a autoridade experimental do piloto.
 */
@Service
public class UnfundedModelClient implements MeteredModelClient {

    static final String NO_AUTHORITY =
            "sem autoridade financeira do circuito de investigação (L6, decisão reservada)";

    private final AIProviderResolver resolver;

    public UnfundedModelClient(AIProviderResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AIResponse complete(AIRequest request) {
        AIProvider provider = resolver.resolve();
        if (!provider.supportsSingleAttempt()) {
            throw new SpendRefusedException("Chamada recusada (não enviada) — o fornecedor " + provider.providerId()
                    + " não garante uma única tentativa contabilizada");
        }
        if (provider.billable()) {
            throw new SpendRefusedException("Chamada recusada (não enviada) — fornecedor facturável "
                    + provider.providerId() + ": " + NO_AUTHORITY);
        }
        return provider.generateSingleAttempt(request);
    }
}
