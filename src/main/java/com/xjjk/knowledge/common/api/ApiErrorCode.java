package com.xjjk.knowledge.common.api;

import org.springframework.http.HttpStatus;

/**
 * 对外稳定错误码。前端只依赖这里的 code，不需要解析异常文案。
 */
public enum ApiErrorCode {
    AUTH_REQUIRED("AUTH_REQUIRED", "请先登录", HttpStatus.UNAUTHORIZED),
    AUTH_INVALID("AUTH_INVALID", "登录状态无效，请重新登录", HttpStatus.UNAUTHORIZED),
    CSRF_INVALID("CSRF_INVALID", "请求校验已失效，请重试", HttpStatus.FORBIDDEN),
    INTERNAL_SIGNATURE_INVALID("INTERNAL_SIGNATURE_INVALID", "内部调用签名无效", HttpStatus.UNAUTHORIZED),
    AUTH_SERVICE_UNAVAILABLE("AUTH_SERVICE_UNAVAILABLE", "认证服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    KNOWLEDGE_ACCESS_DENIED("KNOWLEDGE_ACCESS_DENIED", "无知识库管理权限", HttpStatus.FORBIDDEN),
    TENANT_ACCESS_DENIED("TENANT_ACCESS_DENIED", "无权访问该租户数据", HttpStatus.FORBIDDEN),
    KNOWLEDGE_BASE_NOT_FOUND("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND),
    KNOWLEDGE_BASE_NAME_CONFLICT("KNOWLEDGE_BASE_NAME_CONFLICT", "知识库名称已存在", HttpStatus.CONFLICT),
    KNOWLEDGE_BASE_VERSION_CONFLICT("KNOWLEDGE_BASE_VERSION_CONFLICT", "知识库数据已被其他操作更新", HttpStatus.CONFLICT),
    DOCUMENT_EMPTY("DOCUMENT_EMPTY", "上传文件不能为空", HttpStatus.BAD_REQUEST),
    DOCUMENT_TOO_LARGE("DOCUMENT_TOO_LARGE", "上传文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    DOCUMENT_UNSUPPORTED_TYPE("DOCUMENT_UNSUPPORTED_TYPE", "不支持该文件类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_SIGNATURE_MISMATCH("DOCUMENT_SIGNATURE_MISMATCH", "文件内容与文件类型不匹配", HttpStatus.BAD_REQUEST),
    DOCUMENT_STORAGE_UNAVAILABLE("DOCUMENT_STORAGE_UNAVAILABLE", "文件存储服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_PARSE_FAILED("DOCUMENT_PARSE_FAILED", "文档解析失败", HttpStatus.UNPROCESSABLE_ENTITY),
    DOCUMENT_LIMIT_EXCEEDED("DOCUMENT_LIMIT_EXCEEDED", "文档内容超过处理限制", HttpStatus.PAYLOAD_TOO_LARGE),
    DOCUMENT_OCR_FAILED("DOCUMENT_OCR_FAILED", "文档文字识别失败", HttpStatus.UNPROCESSABLE_ENTITY),
    DOCUMENT_NOT_FOUND("DOCUMENT_NOT_FOUND", "文档或版本不存在", HttpStatus.NOT_FOUND),
    DOCUMENT_CONTENT_UNCHANGED("DOCUMENT_CONTENT_UNCHANGED", "文件内容与已有版本相同，无需重复上传", HttpStatus.CONFLICT),
    DOCUMENT_UNIT_NOT_FOUND("DOCUMENT_UNIT_NOT_FOUND", "文档原文单元不存在", HttpStatus.NOT_FOUND),
    DOCUMENT_NOT_READY("DOCUMENT_NOT_READY", "文档版本尚未完成索引，不能发布", HttpStatus.CONFLICT),
    PUBLICATION_CONFLICT("PUBLICATION_CONFLICT", "发布状态已变化，请刷新后重试", HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED("IDEMPOTENCY_KEY_REUSED", "请求号已用于其他操作或内容", HttpStatus.CONFLICT),
    RELEASE_NO_CHANGES("RELEASE_NO_CHANGES", "发布清单没有变化", HttpStatus.CONFLICT),
    RELEASE_NOT_FOUND("RELEASE_NOT_FOUND", "发布记录不存在", HttpStatus.NOT_FOUND),
    KNOWLEDGE_SERVICE_UNAVAILABLE("KNOWLEDGE_SERVICE_UNAVAILABLE", "知识库检索服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    KNOWLEDGE_MODEL_BUDGET_EXHAUSTED("KNOWLEDGE_MODEL_BUDGET_EXHAUSTED", "知识检索模型本月额度已用尽", HttpStatus.SERVICE_UNAVAILABLE),
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
