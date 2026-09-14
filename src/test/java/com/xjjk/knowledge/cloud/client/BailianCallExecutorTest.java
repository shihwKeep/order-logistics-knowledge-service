package com.xjjk.knowledge.cloud.client;

import com.xjjk.knowledge.cloud.budget.BudgetReservation;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetService;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BailianCallExecutorTest {

    @Test
    void reservesEveryPhysicalAttemptAndUsesExponentialBackoff() {
        CloudModelBudgetService budget = mock(CloudModelBudgetService.class);
        when(budget.reserve("logical-1", CloudModelCallType.EMBEDDING, 1, "model", 100L))
                .thenReturn(reservation("call-1", 1, CloudModelCallType.EMBEDDING));
        when(budget.reserve("logical-1", CloudModelCallType.EMBEDDING, 2, "model", 100L))
                .thenReturn(reservation("call-2", 2, CloudModelCallType.EMBEDDING));
        when(budget.reserve("logical-1", CloudModelCallType.EMBEDDING, 3, "model", 100L))
                .thenReturn(reservation("call-3", 3, CloudModelCallType.EMBEDDING));
        List<Duration> delays = new ArrayList<>();
        BailianCallExecutor executor = new BailianCallExecutor(budget, properties(), delays::add);
        AtomicInteger attempts = new AtomicInteger();

        String value = executor.execute("logical-1", CloudModelCallType.EMBEDDING,
                "model", 100L, () -> {
                    if (attempts.incrementAndGet() < 3) throw new HttpTimeoutException("timeout");
                    return new BailianCallResult<>("ok", 20L, "provider-3");
                });

        assertThat(value).isEqualTo("ok");
        assertThat(delays).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
        verify(budget).markUnknown(reservation("call-1", 1, CloudModelCallType.EMBEDDING), "HttpTimeoutException");
        verify(budget).markUnknown(reservation("call-2", 2, CloudModelCallType.EMBEDDING), "HttpTimeoutException");
        verify(budget).settle(reservation("call-3", 3, CloudModelCallType.EMBEDDING), 20L, "provider-3");
    }

    @Test
    void doesNotRetryNonRetryableProviderRejectionAndReleasesReservation() {
        CloudModelBudgetService budget = mock(CloudModelBudgetService.class);
        BudgetReservation reservation = reservation("call-1", 1, CloudModelCallType.RERANK);
        when(budget.reserve("logical-1", CloudModelCallType.RERANK, 1, "model", 100L))
                .thenReturn(reservation);
        BailianCallExecutor executor = new BailianCallExecutor(budget, properties(), ignored -> {});
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("logical-1", CloudModelCallType.RERANK,
                "model", 100L, () -> {
                    attempts.incrementAndGet();
                    throw new BailianProviderException("InvalidApiKey", 401, false, true);
                })).isInstanceOf(BailianProviderException.class);

        assertThat(attempts).hasValue(1);
        verify(budget).release(reservation, "InvalidApiKey");
    }

    private BudgetReservation reservation(String callId, int attempt, CloudModelCallType type) {
        return new BudgetReservation(callId, "2026-09", 100L,
                type, attempt);
    }

    private BailianModelProperties properties() {
        BailianModelProperties properties = new BailianModelProperties();
        properties.setWorkspaceId("ws-test");
        properties.setApiKey("test-key");
        properties.setMaxAttempts(3);
        properties.setInitialBackoff(Duration.ofMillis(200));
        return properties;
    }
}
