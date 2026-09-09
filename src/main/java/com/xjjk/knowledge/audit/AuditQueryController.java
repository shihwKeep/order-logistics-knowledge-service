package com.xjjk.knowledge.audit;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/audit-logs")
public class AuditQueryController {
    private final AuditQueryService service;

    public AuditQueryController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AuditPage> list(
            @PathVariable @Positive long tenantId,
            @RequestParam(required = false) @Size(max = 60) String action,
            @RequestParam(required = false) @Size(max = 40) String resourceType,
            @RequestParam(required = false) @Size(max = 64) String requestId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int offset,
            @RequestParam(defaultValue = "20") @Positive int limit,
            Authentication authentication) {
        return ApiResponse.success(service.list(
                principal(authentication), tenantId, action, resourceType, requestId, offset, limit));
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }
}
