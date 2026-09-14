package com.xjjk.knowledge.cloud.budget;

public record BudgetReservation(
        String callId,
        String billingMonth,
        long reservedMicros,
        CloudModelCallType callType,
        int attemptNo) {
}
