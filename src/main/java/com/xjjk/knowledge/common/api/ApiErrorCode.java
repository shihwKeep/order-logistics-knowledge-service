package com.xjjk.knowledge.common.api;

import org.springframework.http.HttpStatus;

/**
 * 对外稳定错误码。前端只依赖这里的 code，不需要解析异常文案。
 */
public enum ApiErrorCode {
    AUTH_REQUIRED("AUTH_REQUIRED", "请先登录", HttpStatus.UNAUTHORIZED),
    AUTH_INVALID("AUTH_INVALID", "登录状态无效，请重新登录", HttpStatus.UNAUTHORIZED),
    AUTH_SERVICE_UNAVAILABLE("AUTH_SERVICE_UNAVAILABLE", "认证服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    KNOWLEDGE_ACCESS_DENIED("KNOWLEDGE_ACCESS_DENIED", "无知识库管理权限", HttpStatus.FORBIDDEN),
    TENANT_ACCESS_DENIED("TENANT_ACCESS_DENIED", "无权访问该租户数据", HttpStatus.FORBIDDEN),
    KNOWLEDGE_BASE_NOT_FOUND("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND),
    KNOWLEDGE_BASE_NAME_CONFLICT("KNOWLEDGE_BASE_NAME_CONFLICT", "知识库名称已存在", HttpStatus.CONFLICT),
    KNOWLEDGE_BASE_VERSION_CONFLICT("KNOWLEDGE_BASE_VERSION_CONFLICT", "知识库数据已被其他操作更新", HttpStatus.CONFLICT),
    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ApiErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
