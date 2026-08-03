package com.knowledgeflow.ai.documented;

import org.springframework.stereotype.Service;

/**
 * Resolve o {@link VisibilityLevel}-alvo da projecção por endpoint/contexto (tarefa D8).
 *
 * <p>Centraliza — de forma simples, determinística e conservadora — a escolha da <em>lente</em>
 * de projecção a aplicar pelo {@link AnswerProjectionService}. É o único ponto onde se decide
 * "que vista mostrar", separado de "que resposta dar" (decisão) e de "como projectar"
 * (transformação).
 *
 * <p><strong>D8 não implementa autorização</strong>: a autenticação/autorização continua no
 * mecanismo já existente (Spring Security, {@code @PreAuthorize}). Este serviço apenas
 * traduz o contexto de um endpoint no nível de visibilidade adequado; não lê roles nem
 * decide permissões. Uma futura integração com perfis/roles deve ser feita aqui, sem misturar
 * decisão e projecção (regras 25–27).
 */
@Service
public class VisibilityLevelResolver {

    /**
     * Lente do endpoint de admin ({@code /api/v1/admin/ai/ask}): diagnóstico moderado.
     *
     * @return {@link VisibilityLevel#INTERNAL}
     */
    public VisibilityLevel resolveForAdminAsk() {
        return VisibilityLevel.INTERNAL;
    }

    /**
     * Lente para consulta profissional externa (preparada para integração futura; ainda não
     * ligada a nenhum fluxo).
     *
     * @return {@link VisibilityLevel#EXTERNAL}
     */
    public VisibilityLevel resolveForExternalProfessional() {
        return VisibilityLevel.EXTERNAL;
    }

    /**
     * Lente para ambiente de demonstração — mesma qualidade profissional de {@code EXTERNAL}
     * (preparada para integração futura; ainda não ligada a nenhum fluxo).
     *
     * @return {@link VisibilityLevel#DEMO}
     */
    public VisibilityLevel resolveForDemo() {
        return VisibilityLevel.DEMO;
    }

    /**
     * Lente para curadoria — bastidores completos (preparada para integração futura; ainda
     * não ligada a nenhum fluxo).
     *
     * @return {@link VisibilityLevel#CURATION_ONLY}
     */
    public VisibilityLevel resolveForCuration() {
        return VisibilityLevel.CURATION_ONLY;
    }
}
