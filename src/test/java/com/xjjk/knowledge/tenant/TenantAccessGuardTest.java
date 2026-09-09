package com.xjjk.knowledge.tenant;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAccessGuardTest {

    private final TenantAccessGuard guard = new TenantAccessGuard();

    @Test
    void systemAdminCanManageOnlyOwnTenant() {
        AdminPrincipal systemAdmin = principal(KnowledgeRole.KNOWLEDGE_ADMIN);

        assertThatCode(() -> guard.requireManage(systemAdmin, 1L))
                .doesNotThrowAnyException();
        assertError(
                () -> guard.requireManage(systemAdmin, 2L),
                ApiErrorCode.TENANT_ACCESS_DENIED);
    }

    @Test
    void superAdminCanManageOtherTenantButTenantIdMustBePositive() {
        AdminPrincipal superAdmin = principal(KnowledgeRole.KNOWLEDGE_SUPER_ADMIN);

        assertThatCode(() -> guard.requireManage(superAdmin, 2L))
                .doesNotThrowAnyException();
        assertError(() -> guard.requireManage(superAdmin, 0L), ApiErrorCode.VALIDATION_FAILED);
    }

    private static AdminPrincipal principal(KnowledgeRole role) {
        return new AdminPrincipal(10567L, "74680", "石海文", 1L, Set.of(role));
    }

    private static void assertError(Runnable action, ApiErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
