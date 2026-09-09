package com.xjjk.knowledge.tenant;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.stereotype.Component;

/** 执行知识库管理操作前统一校验目标租户。 */
@Component
public class TenantAccessGuard {

    public void requireManage(AdminPrincipal principal, long targetTenantId) {
        if (targetTenantId <= 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        if (!principal.isSuperAdmin() && principal.tenantId() != targetTenantId) {
            throw new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED);
        }
    }
}
