package com.xjjk.knowledge.common.error;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.UUID;

/**
 * 将异常统一转换为前端可稳定处理的 JSON，避免泄漏堆栈和内部实现细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String REQUEST_ID_ATTRIBUTE = "requestId";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ApiErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode, exception.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            BindException.class
    })
    ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        ApiErrorCode errorCode = ApiErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        Object existingRequestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        String requestId = existingRequestId == null
                ? UUID.randomUUID().toString()
                : existingRequestId.toString();

        // 只记录请求编号和堆栈，禁止记录请求头、Cookie 或请求体中的敏感信息。
        log.error("Unhandled knowledge service exception, requestId={}", requestId, exception);

        ApiErrorCode errorCode = ApiErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode));
    }
}
