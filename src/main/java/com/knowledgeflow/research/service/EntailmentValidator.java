package com.knowledgeflow.research.service;

import java.util.List;
import java.util.Map;

/**
 * Segunda barreira (KF-M02/ARCH-01): avalia se a unidade legal identificada IMPLICA a proposição,
 * dimensão a dimensão. Só é chamada sobre proposições que as verificações determinísticas
 * ({@link AnchorVerifier}, {@link SupportVerifier}) não rejeitaram; o resultado nunca é aceite por
 * si — a decisão final é de {@link EntailmentGate}, determinística, que trata ausência de veredicto,
 * contradição, erro ou incerteza como suporte não demonstrado.
 */
public interface EntailmentValidator {

    enum Verdict { SUPORTA, NAO_SUPORTA, INDETERMINADO }

    /** Dimensões avaliadas expressamente. */
    enum Dimension { SUJEITO, PREDICADO, POLARIDADE, CONDICOES, EXCEPCOES, RESTRICOES, AMBITO_TEMPORAL }

    enum DimensionStatus { CONFORME, NAO_CONFORME, NAO_APLICAVEL, INDETERMINADO }

    /** Uma condição/excepção estruturada da proposição. */
    record Qualifier(String kind, String description) {}

    /**
     * Pedido: a pergunta, a proposição (com as suas condições/excepções), a unidade legal identificada
     * (linhas da âncora, enunciado introdutório e epígrafe) e o texto oficial completo do artigo, para que
     * excepções noutros números possam ser consideradas.
     */
    record Request(String question, String statement, List<Qualifier> qualifiers, String legalReference,
            String legalUnit, String evidenceContent) {}

    /**
     * Resultado do validador, sem decisão. {@code protocolError} não nulo = resposta fora do protocolo
     * (sem JSON, chaves duplicadas, veredicto inválido, texto com veredicto fora do JSON, falha da
     * chamada). {@code call} é a proveniência da chamada ao modelo (nulo se não houve chamada).
     */
    record Assessment(Verdict verdict, Map<Dimension, DimensionStatus> dimensions, Map<Dimension, String> notes,
            String justification, String citedPassage, String protocolError, ModelCall call) {

        public static Assessment protocolFailure(String error, ModelCall call) {
            return new Assessment(null, Map.of(), Map.of(), null, null, error, call);
        }
    }

    /** O validador pode ser chamado (configuração e dependências presentes). */
    boolean isAvailable();

    Assessment assess(Request request);
}
