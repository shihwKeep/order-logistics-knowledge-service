package com.xjjk.knowledge.retrieval.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.service.HybridRetrievalService;
import com.xjjk.knowledge.retrieval.web.dto.RetrieveKnowledgeRequest;
import com.xjjk.knowledge.retrieval.web.dto.RetrieveKnowledgeResponse;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理台检索诊断接口，可显式检查草稿层或发布层。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge")
public class AdminRetrievalController {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final HybridRetrievalService service;
    private final TenantAccessGuard tenantAccessGuard;

    public AdminRetrievalController(
            HybridRetrievalService service, TenantAccessGuard tenantAccessGuard) {
        this.service = service;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @PostMapping("/retrieve")
    public ApiResponse<RetrieveKnowledgeResponse> retrieve(
            @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "PUBLISHED") IndexLayer layer,
            @Valid @RequestBody RetrieveKnowledgeRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        AdminPrincipal principal = principal(authentication);
        tenantAccessGuard.requireManage(principal, tenantId);
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(RetrieveKnowledgeResponse.from(service.retrieveAdmin(
                tenantId, principal.userId(), requestId, body.question(), body.knowledgeBaseIds(), layer)));
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }

    private String prepareRequestId(HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied == null || supplied.isBlank()
                || supplied.length() > MAX_REQUEST_ID_LENGTH
                ? UUID.randomUUID().toString() : supplied;
        request.setAttribute("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return requestId;
    }
}
