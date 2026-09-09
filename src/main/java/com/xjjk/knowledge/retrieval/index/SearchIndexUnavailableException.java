package com.xjjk.knowledge.retrieval.index;

public class SearchIndexUnavailableException extends RuntimeException {
    public SearchIndexUnavailableException(String message) {
        super(message);
    }

    public SearchIndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
