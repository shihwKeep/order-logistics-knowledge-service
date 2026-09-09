package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.task.IngestionTask;
import java.time.LocalDateTime;

public record IngestionTaskResponse(
        long id,
        String stage,
        String status,
        int retryCount,
        LocalDateTime nextRunAt,
        LocalDateTime lockedUntil,
        String lastErrorCode) {
    public static IngestionTaskResponse from(IngestionTask task) {
        return new IngestionTaskResponse(
                task.id(), task.stage(), task.status(), task.retryCount(),
                task.nextRunAt(), task.lockedUntil(), task.lastErrorCode());
    }
}
