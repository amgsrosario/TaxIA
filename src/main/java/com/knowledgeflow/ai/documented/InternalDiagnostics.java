package com.knowledgeflow.ai.documented;

import java.util.List;

/**
 * Diagnóstico interno da resposta documentada (tarefa D11).
 *
 * <p>Explica o <em>caminho técnico</em> até à conclusão profissional: que sinais de decisão,
 * fontes, risco, actualidade, diversidade e projecção foram observados. É deliberadamente
 * conservador — regista apenas <strong>nomes de sinais, contagens e nomes de enumerados</strong>,
 * nunca conteúdo sensível (chunks, scores em bruto, ranking, prompts, logs, notas internas,
 * excertos, títulos, referências ou identificadores técnicos).
 *
 * <p>Princípios de contrato:
 * <ul>
 *   <li><strong>Só runtime</strong> — nunca é persistido, não cria colunas nem auditoria em BD
 *       (regras 16–18).</li>
 *   <li><strong>Observa, não decide</strong> — não recalcula {@link AnswerType} nem
 *       {@link ParecerRequirement}; lê a decisão já tomada pelo {@link AnswerDecisionService}
 *       (regras 22–24).</li>
 *   <li><strong>Bastidores</strong> — {@code EXTERNAL}/{@code DEMO} nunca o expõem; só
 *       {@code INTERNAL}/{@code CURATION_ONLY} o preservam (regras 20–21).</li>
 * </ul>
 *
 * <p>«A resposta externa mostra a conclusão profissional. O diagnóstico interno mostra o
 * caminho técnico até lá.»
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D11), secção 12.H.
 *
 * @param decisionSignals sinais de forma/prudência lidos da decisão (support, tipo, parecer)
 * @param sourceSignals sinais qualitativos das fontes (presença de oficial/legal, fraqueza…)
 * @param riskSignals sinais de risco agregado (ausência de cálculo real, não confusão com entidade)
 * @param freshnessSignals sinais de actualidade (incerta/desactualizada, sem verificação online)
 * @param diversitySignals sinais de diversidade material (mesmo núcleo, eco documental…)
 * @param projectionSignals política de projecção por visibilidade que este diagnóstico segue
 * @param hiddenForExternal nomes/tipos de campos ocultados a {@code EXTERNAL}/{@code DEMO}
 * @param warningsInternal cautelas internas (sem valores sensíveis) para curadoria/revisão
 */
public record InternalDiagnostics(
        List<String> decisionSignals,
        List<String> sourceSignals,
        List<String> riskSignals,
        List<String> freshnessSignals,
        List<String> diversitySignals,
        List<String> projectionSignals,
        List<String> hiddenForExternal,
        List<String> warningsInternal
) {

    /** Diagnóstico vazio (nunca {@code null}) para caminhos sem contexto. */
    public static InternalDiagnostics empty() {
        return new InternalDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
