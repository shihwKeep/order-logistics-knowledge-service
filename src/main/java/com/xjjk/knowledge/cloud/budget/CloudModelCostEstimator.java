package com.xjjk.knowledge.cloud.budget;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CloudModelCostEstimator {
    /** 百炼 Rerank usage 会额外计入一个请求级框架 Token。 */
    private static final long RERANK_REQUEST_FRAME_TOKENS = 1L;

    public long embeddingMaximumCharge(List<String> texts, String instruction,
                                       long priceMicrosPerMillionTokens) {
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("向量文本不能为空");
        long bytes = texts.stream().mapToLong(this::utf8Bytes).sum();
        if (instruction != null && !instruction.isBlank()) {
            bytes = Math.addExact(bytes, Math.multiplyExact(
                    utf8Bytes(instruction), texts.size()));
        }
        return charge(bytes, priceMicrosPerMillionTokens);
    }

    public long rerankMaximumCharge(String query, List<String> documents, String instruction,
                                    long priceMicrosPerMillionTokens) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("精排文本不能为空");
        }
        long bytes = Math.multiplyExact(utf8Bytes(query), documents.size());
        for (String document : documents) bytes = Math.addExact(bytes, utf8Bytes(document));
        if (instruction != null && !instruction.isBlank()) bytes = Math.addExact(bytes, utf8Bytes(instruction));
        bytes = Math.addExact(bytes, RERANK_REQUEST_FRAME_TOKENS);
        return charge(bytes, priceMicrosPerMillionTokens);
    }

    public long actualCharge(long totalTokens, long priceMicrosPerMillionTokens) {
        if (totalTokens <= 0) throw new IllegalArgumentException("Token 用量必须大于零");
        return charge(totalTokens, priceMicrosPerMillionTokens);
    }

    private long charge(long tokenUpperBound, long price) {
        if (tokenUpperBound <= 0 || price <= 0) throw new IllegalArgumentException("计费参数必须大于零");
        return Math.max(1L, Math.addExact(Math.multiplyExact(tokenUpperBound, price), 999_999L)
                / 1_000_000L);
    }

    private long utf8Bytes(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("计费文本不能为空");
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
