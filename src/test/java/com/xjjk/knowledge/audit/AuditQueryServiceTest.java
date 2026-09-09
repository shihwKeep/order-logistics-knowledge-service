package com.xjjk.knowledge.audit;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditQueryServiceTest {
    @Test
    void checksTenantAndReturnsBoundedPage() {
        AuditQueryRepository repository = mock(AuditQueryRepository.class);
        TenantAccessGuard guard = mock(TenantAccessGuard.class);
        AuditLogEntry entry = new AuditLogEntry(
                1L, 7L, 10567L, 7L, "DOCUMENT_PUBLISH", "DOCUMENT_VERSION",
                "42", "request-1", "SUCCESS", "{\"versionId\":9}", LocalDateTime.now());
        when(repository.count(7L, "DOCUMENT_PUBLISH", null, null)).thenReturn(1L);
        when(repository.list(7L, "DOCUMENT_PUBLISH", null, null, 0, 100)).thenReturn(List.of(entry));
        AuditQueryService service = new AuditQueryService(guard, repository);

        AuditPage result = service.list(principal(), 7L, " DOCUMENT_PUBLISH ", "", "", -5, 500);

        verify(guard).requireManage(principal(), 7L);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).containsExactly(entry);
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(
                10567L, "74680", "石海文", 7L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }
}
