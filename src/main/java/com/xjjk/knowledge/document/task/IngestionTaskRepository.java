package com.xjjk.knowledge.document.task;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface IngestionTaskRepository {
    Optional<IngestionTaskLease> claim(long taskId, String workerId, Duration leaseDuration);
    Optional<IngestionTask> find(long taskId);
    List<Long> findDueTaskIds(int limit);
    boolean renew(long taskId, String leaseToken, Duration leaseDuration);
    boolean complete(long taskId, String leaseToken);
    boolean defer(long taskId, String leaseToken, String errorCode, String message, Duration delay);
    boolean fail(long taskId, String leaseToken, String errorCode, String message, int maxRetries, Duration baseDelay);
}
