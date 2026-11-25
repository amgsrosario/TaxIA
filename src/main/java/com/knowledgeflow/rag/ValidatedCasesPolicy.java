package com.knowledgeflow.rag;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M0 — decide, uma vez no arranque, se os casos {@code VALIDATED} ({@code knowledge_cases}) podem entrar na pesquisa RAG
 * e, por isso, fundamentar respostas.
 *
 * <p>Excluídos se {@code knowledgeflow.rag.validated-cases-enabled=false} (valor do perfil {@code pilot}) e
 * <b>sempre</b> que a base for a {@code knowledgeflow_pilot}, seja qual for o perfil ou a configuração: o launcher do
 * piloto usa o perfil {@code dev}. Base não identificável: excluídos. Nenhum caso é eliminado nem muda de estado.
 */
@Component
public class ValidatedCasesPolicy {

    private static final Logger log = LoggerFactory.getLogger(ValidatedCasesPolicy.class);

    static final String PILOT_DATABASE = "knowledgeflow_pilot";

    private final boolean included;

    @Autowired
    public ValidatedCasesPolicy(
            @Value("${knowledgeflow.rag.validated-cases-enabled:false}") boolean enabled, DataSource dataSource) {
        this.included = enabled && !isPilotOrUnknown(dataSource);
        log.info("RAG validated cases: {} (configured: {})", included ? "INCLUDED" : "EXCLUDED", enabled);
    }

    /** Os casos {@code VALIDATED} participam na pesquisa RAG. */
    public boolean included() {
        return included;
    }

    /** Read-only metadata check, fail-closed. */
    private static boolean isPilotOrUnknown(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                log.warn("RAG validated cases: database not identified; cases excluded until restart");
                return true;
            }
            return PILOT_DATABASE.equals(catalog.trim().toLowerCase(Locale.ROOT));
        } catch (SQLException e) {
            log.warn("RAG validated cases: database not identified ({}); cases excluded until restart",
                    e.getClass().getSimpleName());
            return true;
        }
    }
}
