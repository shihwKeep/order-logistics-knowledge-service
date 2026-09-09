package com.xjjk.knowledge.knowledgebase.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import com.xjjk.knowledge.knowledgebase.web.dto.CreateKnowledgeBaseRequest;
import com.xjjk.knowledge.knowledgebase.web.dto.KnowledgeBaseResponse;
import com.xjjk.knowledge.knowledgebase.web.dto.UpdateKnowledgeBaseRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 租户路径显式化的知识库管理接口。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge-bases")
public class KnowledgeBaseController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list(
            @PathVariable @Positive long tenantId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        List<KnowledgeBaseResponse> result = service.list(principal(authentication), tenantId)
                .stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(
            @PathVariable @Positive long tenantId,
            @Valid @RequestBody CreateKnowledgeBaseRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(KnowledgeBaseResponse.from(service.create(
                principal(authentication),
                tenantId,
                body.name(),
                body.description(),
                requestId)));
    }

    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> get(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(KnowledgeBaseResponse.from(service.get(
                principal(authentication), tenantId, knowledgeBaseId)));
    }

    @PutMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> update(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @Valid @RequestBody UpdateKnowledgeBaseRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(KnowledgeBaseResponse.from(service.update(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                body.expectedVersion(),
                body.name(),
                body.description(),
                requestId)));
    }

    @PostMapping("/{knowledgeBaseId}/enable")
    public ApiResponse<KnowledgeBaseResponse> enable(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        return changeStatus(
                tenantId,
                knowledgeBaseId,
                KnowledgeBaseStatus.ENABLED,
                authentication,
                request,
                response);
    }

    @PostMapping("/{knowledgeBaseId}/disable")
    public ApiResponse<KnowledgeBaseResponse> disable(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        return changeStatus(
                tenantId,
                knowledgeBaseId,
                KnowledgeBaseStatus.DISABLED,
                authentication,
                request,
                response);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ApiResponse<Void> delete(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        service.delete(principal(authentication), tenantId, knowledgeBaseId, requestId);
        return ApiResponse.success(null);
    }

    private ApiResponse<KnowledgeBaseResponse> changeStatus(
            long tenantId,
            long knowledgeBaseId,
            KnowledgeBaseStatus status,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(KnowledgeBaseResponse.from(service.setStatus(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                status,
                requestId)));
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }

    private String prepareRequestId(
            HttpServletRequest request,
            HttpServletResponse response) {
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
