package com.xjjk.knowledge.publication.release.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.publication.release.ReleaseReplacement;
import com.xjjk.knowledge.publication.release.ReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases")
public class ReleaseController {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReleaseResponse>> create(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @Valid @RequestBody CreateReleaseRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        List<ReleaseReplacement> replacements = body.replacements().stream()
                .map(item -> new ReleaseReplacement(item.documentId(), item.versionId()))
                .toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                ReleaseResponse.from(service.create(
                        principal(authentication), tenantId, knowledgeBaseId,
                        replacements, requestId))));
    }

    @GetMapping
    public ApiResponse<List<ReleaseResponse>> list(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(service.list(
                        principal(authentication), tenantId, knowledgeBaseId)
                .stream().map(ReleaseResponse::from).toList());
    }

    @GetMapping("/{releaseId}")
    public ApiResponse<ReleaseDetailResponse> detail(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long releaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        AdminPrincipal principal = principal(authentication);
        return ApiResponse.success(ReleaseDetailResponse.from(
                service.get(principal, tenantId, knowledgeBaseId, releaseId),
                service.items(principal, tenantId, knowledgeBaseId, releaseId)));
    }

    @PostMapping("/{releaseId}/rollback")
    public ResponseEntity<ApiResponse<ReleaseResponse>> rollback(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long releaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                ReleaseResponse.from(service.rollback(
                        principal(authentication), tenantId, knowledgeBaseId,
                        releaseId, requestId))));
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
            HttpServletRequest request, HttpServletResponse response) {
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
