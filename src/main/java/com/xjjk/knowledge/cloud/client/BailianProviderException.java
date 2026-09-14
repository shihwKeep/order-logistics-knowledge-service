package com.xjjk.knowledge.cloud.client;

public class BailianProviderException extends RuntimeException {
    private final String errorCode;
    private final int statusCode;
    private final boolean retryable;
    private final boolean provablyUnbilled;

    public BailianProviderException(String errorCode, int statusCode,
                                    boolean retryable, boolean provablyUnbilled) {
        super("百炼服务调用失败: " + safe(errorCode));
        this.errorCode = safe(errorCode);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.provablyUnbilled = provablyUnbilled;
    }

    public BailianProviderException(String errorCode, Throwable cause) {
        super("百炼服务调用失败: " + safe(errorCode), cause);
        this.errorCode = safe(errorCode);
        this.statusCode = 0;
        this.retryable = false;
        this.provablyUnbilled = false;
    }

    public String errorCode() { return errorCode; }
    public int statusCode() { return statusCode; }
    public boolean retryable() { return retryable; }
    public boolean provablyUnbilled() { return provablyUnbilled; }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN_PROVIDER_ERROR" : value;
    }
}
