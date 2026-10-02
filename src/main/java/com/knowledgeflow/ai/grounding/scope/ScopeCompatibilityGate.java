package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Regras de contradição de âmbito (M4-SCOPE). Não prova que a Q&amp;A responde à pergunta; só rejeita
 * quando a pergunta contradiz explicitamente o âmbito da candidata. Silêncio numa dimensão — de
 * qualquer dos lados — nunca é contradição.
 *
 * <ul>
 *   <li>Imposto: ambos indicam imposto(s) e não há nenhum em comum → {@code DOMAIN_MISMATCH}
 *       (uma pergunta sobre vários impostos passa se um deles for o da candidata).</li>
 *   <li>Categoria: ambos indicam categoria(s) e a candidata não cobre todas as da pergunta →
 *       {@code CATEGORY_NOT_COVERED} (pensão + salário não cabe numa Q&amp;A só de pensões). É a
 *       regra mais estrita: uma categoria mencionada de passagem também conta, por isso o dicionário
 *       só reconhece categorias pelo rendimento, nunca pela pessoa ("reformado", "pensionista").</li>
 *   <li>Operação: ambos indicam operação(ões) e não há nenhuma em comum →
 *       {@code OPERATION_MISMATCH}.</li>
 *   <li>Aplicabilidade (M4-SCOPE-V2): a pergunta tem um marcador que a Q&amp;A declara como exclusão
 *       efectiva → {@code APPLICABILITY_EXCLUDED}. Sem marcador ou sem exclusões, nada muda.</li>
 * </ul>
 */
@Component
public class ScopeCompatibilityGate {

    public ScopeDecision decide(FiscalScope query, FiscalScope candidate) {
        if (!query.domains().isEmpty() && !candidate.domains().isEmpty()
                && query.domains().stream().noneMatch(candidate.domains()::contains)) {
            return ScopeDecision.reject(ScopeReason.DOMAIN_MISMATCH);
        }
        if (!query.incomeCategories().isEmpty() && !candidate.incomeCategories().isEmpty()
                && !candidate.incomeCategories().containsAll(query.incomeCategories())) {
            return ScopeDecision.reject(ScopeReason.CATEGORY_NOT_COVERED);
        }
        if (!query.operations().isEmpty() && !candidate.operations().isEmpty()
                && query.operations().stream().noneMatch(candidate.operations()::contains)) {
            return ScopeDecision.reject(ScopeReason.OPERATION_MISMATCH);
        }
        return ScopeDecision.allow(ScopeReason.NO_CONTRADICTION);
    }

    /** Contradições v1 primeiro (imposto, categoria, operação); depois as exclusões de aplicabilidade. */
    public ScopeDecision decide(FiscalScope query, Set<ApplicabilityMarker> queryMarkers, CandidateScope candidate) {
        ScopeDecision v1 = decide(query, candidate.scope());
        if (!v1.allowed()) {
            return v1;
        }
        if (queryMarkers.stream().anyMatch(candidate.exclusions()::contains)) {
            return ScopeDecision.reject(ScopeReason.APPLICABILITY_EXCLUDED);
        }
        return v1;
    }
}
