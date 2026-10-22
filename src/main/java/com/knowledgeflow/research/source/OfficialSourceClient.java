package com.knowledgeflow.research.source;

/**
 * Obtenção read-only de uma página de fonte oficial. A implementação real
 * ({@link OfficialSourceHttpClient}) aplica a allowlist; os testes usam fixtures.
 */
public interface OfficialSourceClient {

    enum Outcome { FETCHED, NOT_FOUND, BLOCKED, FAILED }

    /** Resultado da consulta. {@code body} só existe quando {@code outcome == FETCHED}. */
    record FetchedPage(Outcome outcome, Integer httpStatus, String finalUrl, String body, String detail) {

        public static FetchedPage fetched(int status, String finalUrl, String body) {
            return new FetchedPage(Outcome.FETCHED, status, finalUrl, body, null);
        }

        public static FetchedPage notFound(int status, String url) {
            return new FetchedPage(Outcome.NOT_FOUND, status, url, null, "HTTP " + status);
        }

        public static FetchedPage blocked(Integer status, String url, String detail) {
            return new FetchedPage(Outcome.BLOCKED, status, url, null, detail);
        }

        public static FetchedPage failed(Integer status, String url, String detail) {
            return new FetchedPage(Outcome.FAILED, status, url, null, detail);
        }
    }

    FetchedPage fetch(String url);
}
