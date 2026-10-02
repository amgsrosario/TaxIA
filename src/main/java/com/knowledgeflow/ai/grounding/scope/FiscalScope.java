package com.knowledgeflow.ai.grounding.scope;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Âmbito fiscal detectado num texto (pergunta do utilizador ou Q&amp;A candidata) — M4-SCOPE.
 *
 * <p>Um conjunto vazio numa dimensão significa "o texto não diz nada" (silêncio), nunca
 * "incompatível": o {@link ScopeCompatibilityGate} só rejeita por contradição explícita.
 */
public record FiscalScope(
        Set<TaxDomain> domains,
        Set<IncomeCategory> incomeCategories,
        Set<FiscalOperation> operations
) {

    public FiscalScope {
        domains = Collections.unmodifiableSet(copy(domains, TaxDomain.class));
        incomeCategories = Collections.unmodifiableSet(copy(incomeCategories, IncomeCategory.class));
        operations = Collections.unmodifiableSet(copy(operations, FiscalOperation.class));
    }

    private static <E extends Enum<E>> EnumSet<E> copy(Set<E> values, Class<E> type) {
        return values == null || values.isEmpty() ? EnumSet.noneOf(type) : EnumSet.copyOf(values);
    }

    /** Imposto. */
    public enum TaxDomain { IRS, IVA, IRC, IMI, AIMI, IMT }

    /** Categoria de rendimentos do IRS. */
    public enum IncomeCategory { A, B, F, G, H }

    /** Operação/obrigação fiscal sobre a qual a pergunta incide. */
    public enum FiscalOperation {
        RETENCAO, DEDUCAO, CONSERVACAO, TRIBUTACAO_CONJUNTA, CALCULO, DECLARACAO, EMISSAO
    }
}
