package com.xjjk.knowledge.retrieval.embedding;

/** Embedding 服务不可用或返回不可信向量。 */
public class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
