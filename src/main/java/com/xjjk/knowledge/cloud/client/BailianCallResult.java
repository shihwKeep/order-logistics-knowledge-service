package com.xjjk.knowledge.cloud.client;

public record BailianCallResult<T>(T value, long totalTokens, String providerRequestId) {
    public BailianCallResult {
        if (value == null || totalTokens <= 0) {
            throw new IllegalArgumentException("百炼成功响应必须包含结果和 Token 用量");
        }
    }
}
