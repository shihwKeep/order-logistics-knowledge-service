package com.xjjk.knowledge.cloud.budget;

import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudModelBudgetServiceTest {
    private CloudModelBudgetMapper mapper;
    private CloudModelBudgetService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CloudModelBudgetMapper.class);
        BailianModelProperties properties = new BailianModelProperties();
        properties.setWorkspaceId("ws-test");
        properties.setApiKey("test-key");
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T16:00:00Z"), ZoneId.of("UTC"));
        service = new CloudModelBudgetService(mapper, properties,
                new CloudModelCostEstimator(), clock);
    }

    @Test
    void reserveUsesShanghaiBillingMonth() {
        when(mapper.reserve(eq("2026-09"), eq(10L), any())).thenReturn(1);
        when(mapper.insertCall(any(), eq("2026-09"), eq("request-1"), eq(1),
                eq("EMBEDDING"), eq("qwen3.7-text-embedding"), eq(10L), any())).thenReturn(1);

        BudgetReservation reservation = service.reserve("request-1", CloudModelCallType.EMBEDDING,
                1, "qwen3.7-text-embedding", 10L);

        assertThat(reservation.billingMonth()).isEqualTo("2026-09");
    }

    @Test
    void reserveFailsClosedWhenLimitWouldBeExceeded() {
        when(mapper.reserve(any(), eq(10L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reserve("request-1", CloudModelCallType.EMBEDDING,
                1, "qwen3.7-text-embedding", 10L))
                .isInstanceOf(CloudModelBudgetExceededException.class);

        verify(mapper, never()).insertCall(any(), any(), any(), eq(1), any(), any(), eq(10L), any());
    }

    @Test
    void settleReleasesSurplusAndUsesProviderTokens() {
        BudgetReservation reservation = new BudgetReservation(
                "call-1", "2026-09", 100L, CloudModelCallType.RERANK, 1);
        when(mapper.settleAccount("2026-09", 100L, 40L, service.now())).thenReturn(1);
        when(mapper.markSettled(eq("call-1"), eq(40L), eq(79L), eq("provider-1"), any()))
                .thenReturn(1);

        service.settle(reservation, 79L, "provider-1");

        verify(mapper).settleAccount(eq("2026-09"), eq(100L), eq(40L), any());
    }

    @Test
    void unknownKeepsAccountReservation() {
        BudgetReservation reservation = new BudgetReservation(
                "call-1", "2026-09", 100L, CloudModelCallType.EMBEDDING, 1);
        when(mapper.markUnknown(eq("call-1"), eq("TIMEOUT"), any())).thenReturn(1);

        service.markUnknown(reservation, "TIMEOUT");

        verify(mapper, never()).releaseAccount(any(), any(Long.class), any());
        verify(mapper).markUnknown(eq("call-1"), eq("TIMEOUT"), any());
    }
}
