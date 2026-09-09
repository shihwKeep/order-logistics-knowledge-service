package com.xjjk.knowledge.common.error;

import com.xjjk.knowledge.common.api.ApiErrorCode;

import java.util.Objects;

/**
 * 可预期的业务异常，由全局异常处理器转换成稳定错误码。
 */
public class BusinessException extends RuntimeException {

    private final ApiErrorCode errorCode;

    public BusinessException(ApiErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }
}
