package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.service.DocumentUploadService;
import com.xjjk.knowledge.document.web.dto.DocumentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** 租户路径显式化的文档管理入口。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final DocumentUploadService uploadService;

    public DocumentController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<DocumentResponse> upload(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        try {
            return ApiResponse.success(DocumentResponse.from(uploadService.upload(
                    principal(authentication),
                    tenantId,
                    knowledgeBaseId,
                    title,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    requestId)));
        } catch (IOException exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }

    private String prepareRequestId(HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied == null
                || supplied.isBlank()
                || supplied.length() > MAX_REQUEST_ID_LENGTH
                ? UUID.randomUUID().toString()
                : supplied;
        request.setAttribute("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return requestId;
    }
}
