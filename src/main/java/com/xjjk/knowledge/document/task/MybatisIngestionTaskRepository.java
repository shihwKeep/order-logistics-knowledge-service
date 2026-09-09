package com.xjjk.knowledge.document.task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisIngestionTaskRepository implements IngestionTaskRepository {
    private final IngestionTaskMapper mapper;

    public MybatisIngestionTaskRepository(IngestionTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IngestionTaskLease> claim(long taskId, String workerId, Duration leaseDuration) {
        String token = UUID.randomUUID().toString();
        if (mapper.claim(taskId, token, workerId, LocalDateTime.now().plus(leaseDuration)) != 1) {
            return Optional.empty();
        }
        IngestionTask task = mapper.find(taskId);
        if (task == null || !token.equals(task.leaseToken())) {
            return Optional.empty();
        }
        return Optional.of(new IngestionTaskLease(
                task.id(), task.tenantId(), task.knowledgeBaseId(), task.documentId(),
                task.versionId(), task.stage(), token));
    }

    @Override
    public Optional<IngestionTask> find(long taskId) {
        return Optional.ofNullable(mapper.find(taskId));
    }

    @Override
    public List<Long> findDueTaskIds(int limit) {
        return mapper.findDueTaskIds(limit);
    }

    @Override
    public boolean renew(long taskId, String leaseToken, Duration leaseDuration) {
        return mapper.renew(taskId, leaseToken, LocalDateTime.now().plus(leaseDuration)) == 1;
    }

    @Override
    public boolean complete(long taskId, String leaseToken) {
        return mapper.complete(taskId, leaseToken) == 1;
    }

    @Override
    public boolean fail(long taskId, String leaseToken, String errorCode, String message, int maxRetries, Duration baseDelay) {
        IngestionTask task = mapper.find(taskId);
        if (task == null || !leaseToken.equals(task.leaseToken())) {
            return false;
        }
        int nextAttempt = task.retryCount() + 1;
        String status = nextAttempt >= maxRetries ? "DEAD" : "RETRY";
        long multiplier = 1L << Math.min(task.retryCount(), 8);
        LocalDateTime nextRunAt = LocalDateTime.now().plus(baseDelay.multipliedBy(multiplier));
        String safeMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
        return mapper.fail(taskId, leaseToken, status, nextRunAt, errorCode, safeMessage) == 1;
    }
}
