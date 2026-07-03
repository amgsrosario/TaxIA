package com.knowledgeflow.ingestion.atfaq;

/** Exceptions of the AT FAQ ingestion module. */
public final class AtFaqExceptions {

    private AtFaqExceptions() {
    }

    /** A security rule fired: disallowed host, external redirect, invalid URL. Aborts the run. */
    public static class SecurityBlockedException extends RuntimeException {
        public SecurityBlockedException(String message) {
            super(message);
        }
    }

    /** The source answered 403/429/503 — we stop immediately and never work around it. */
    public static class SourceBlockedException extends RuntimeException {
        private final int statusCode;

        public SourceBlockedException(int statusCode, String url) {
            super("Source answered HTTP %d for %s — stopping the run".formatted(statusCode, url));
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /** Item/page-level fetch failure (timeout, oversized body, unexpected status). */
    public static class FetchException extends RuntimeException {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
