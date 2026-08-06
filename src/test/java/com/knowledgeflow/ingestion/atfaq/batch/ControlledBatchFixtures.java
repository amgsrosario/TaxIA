package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.List;

/**
 * Local, typed fixtures for the controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>These are Java test builders standing in for a fixture file — all data is local and
 * fictitious. No URL here is ever fetched: {@code sourceUrl} is a plain string only. The
 * batch deliberately covers the governance spectrum so the simulation can be asserted:
 *
 * <ol>
 *   <li>{@code clean} — official, low risk, technical answer + legal reference → AUTO_CONTROLLED</li>
 *   <li>{@code noLegalFoundation} — valid Q&amp;A but no legal reference → ASSISTED</li>
 *   <li>{@code exactDuplicate} — identical content to {@code clean} → NOT_PUBLISHABLE</li>
 *   <li>{@code conflict} — explicit conflict marker → MANUAL_REQUIRED</li>
 *   <li>{@code highRisk} — HIGH risk → MANUAL_REQUIRED</li>
 *   <li>{@code noTechnicalAnswer} — missing technical answer → NOT_PUBLISHABLE</li>
 * </ol>
 */
final class ControlledBatchFixtures {

    private ControlledBatchFixtures() {
    }

    static final String CLEAN_QUESTION = "Qual a periodicidade do IVA para volume de negócios superior a 650.000€?";
    static final String CLEAN_ANSWER =
            "Sujeitos passivos com volume de negócios igual ou superior a 650.000€ ficam enquadrados "
                    + "no regime mensal de IVA.";

    /** (1) Clean, auto-controllable candidate. */
    static AtFaqControlledBatchItem clean() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1001",
                "faq://at/local/iva-periodicidade-mensal",
                "FAQ AT — Periodicidade do IVA",
                CLEAN_QUESTION,
                CLEAN_ANSWER,
                "Nos termos do artigo 41.º do CIVA, o enquadramento no regime mensal é obrigatório quando "
                        + "o volume de negócios do ano civil anterior atinge ou excede 650.000€.",
                "IVA",
                KnowledgeRiskLevel.LOW,
                "Artigo 41.º do CIVA",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);
    }

    /** (2) Valid Q&A, but without a clear legal foundation → assisted curation. */
    static AtFaqControlledBatchItem noLegalFoundation() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1002",
                "faq://at/local/prazo-entrega-declaracao",
                "FAQ AT — Prazo de entrega",
                "Até quando posso entregar a declaração periódica quando o prazo termina ao fim de semana?",
                "Quando o prazo termina a um sábado, domingo ou feriado, transita para o primeiro dia útil seguinte.",
                "O termo do prazo que recaia em dia não útil transfere-se para o dia útil imediatamente seguinte.",
                "Obrigações declarativas",
                KnowledgeRiskLevel.LOW,
                null, // no legal reference → ASSISTED
                true,
                FreshnessStatus.CURRENT,
                false,
                false);
    }

    /** (3) Exact duplicate of {@link #clean()} (same question + answer). */
    static AtFaqControlledBatchItem exactDuplicate() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1003",
                "faq://at/local/iva-periodicidade-mensal-copia",
                "FAQ AT — Periodicidade do IVA (cópia)",
                CLEAN_QUESTION,
                CLEAN_ANSWER,
                "Cópia sem valor acrescentado do item AT-FAQ-1001.",
                "IVA",
                KnowledgeRiskLevel.LOW,
                "Artigo 41.º do CIVA",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);
    }

    /** (4) Explicitly marked as conflicting with existing knowledge → manual decision. */
    static AtFaqControlledBatchItem conflict() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1004",
                "faq://at/local/iva-limiar-conflito",
                "FAQ AT — Limiar IVA (versão em conflito)",
                "Qual o limiar de volume de negócios para o regime mensal de IVA?",
                "O limiar é de 500.000€.", // diverge do conhecimento existente
                "Valor divergente do artigo 41.º do CIVA — carece de reconciliação humana.",
                "IVA",
                KnowledgeRiskLevel.LOW,
                "Artigo 41.º do CIVA",
                true,
                FreshnessStatus.CURRENT,
                true, // conflictMarker
                false);
    }

    /** (5) High-risk item → manual decision / parecer. */
    static AtFaqControlledBatchItem highRisk() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1005",
                "faq://at/local/dedutibilidade-viaturas",
                "FAQ AT — Dedutibilidade de IVA em viaturas",
                "É dedutível o IVA na aquisição de uma viatura ligeira de passageiros?",
                "Em regra não é dedutível, com excepções que dependem do uso e do tipo de viatura.",
                "A dedutibilidade depende de condições específicas do artigo 21.º do CIVA e das excepções aplicáveis; "
                        + "matéria sujeita a interpretação e contingência.",
                "IVA",
                KnowledgeRiskLevel.HIGH,
                "Artigo 21.º do CIVA",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);
    }

    /** (6) No technical answer → cannot be published. */
    static AtFaqControlledBatchItem noTechnicalAnswer() {
        return new AtFaqControlledBatchItem(
                "AT-FAQ-1006",
                "faq://at/local/pergunta-sem-resposta-tecnica",
                "FAQ AT — Sem resposta técnica",
                "Como se calcula o coeficiente de imputação específica?",
                "Depende de vários factores; consultar tabela.",
                "   ", // blank technical answer → NOT_PUBLISHABLE
                "IRC",
                KnowledgeRiskLevel.LOW,
                "Referência a confirmar",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);
    }

    /** The full 6-item controlled batch, in a stable order. */
    static List<AtFaqControlledBatchItem> sixItemBatch() {
        return List.of(
                clean(),
                noLegalFoundation(),
                exactDuplicate(),
                conflict(),
                highRisk(),
                noTechnicalAnswer());
    }
}
