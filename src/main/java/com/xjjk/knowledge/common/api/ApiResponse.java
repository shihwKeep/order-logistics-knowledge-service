package com.xjjk.knowledge.common.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 管理端接口的统一响应结构。
 *
 * @param code      稳定的机器可读业务码
 * @param message   面向调用方的简短说明
 * @param data      成功时的业务数据，失败时为空
 * @param timestamp 服务端生成响应的北京时间
 */
public record ApiResponse<T>(String code, String message, T data, OffsetDateTime timestamp) {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "success", data, OffsetDateTime.now(BUSINESS_ZONE));
    }

    public static ApiResponse<Void> failure(ApiErrorCode errorCode) {
        return failure(errorCode, errorCode.message());
    }

    public static ApiResponse<Void> failure(ApiErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, OffsetDateTime.now(BUSINESS_ZONE));
    }
}
