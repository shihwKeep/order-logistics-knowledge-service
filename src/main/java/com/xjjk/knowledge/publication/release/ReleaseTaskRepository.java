package com.xjjk.knowledge.publication.release;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseTaskRepository {
    private final ReleaseTaskMapper mapper;
    private final ReleaseProperties properties;

    public ReleaseTaskRepository(ReleaseTaskMapper mapper, ReleaseProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<Long> findDueIds(int limit) {
        return mapper.findDueIds(limit);
    }

    public Optional<ReleaseTaskLease> claim(
            long taskId, String workerId, java.time.Duration leaseDuration) {
        String token = UUID.randomUUID().toString();
        if (mapper.claim(taskId, token, workerId, LocalDateTime.now().plus(leaseDuration)) != 1) {
            return Optional.empty();
        }
        ReleaseTask task = mapper.find(taskId);
        if (task == null || !token.equals(task.leaseToken())) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseTaskLease(
                task.id(), task.releaseId(), task.tenantId(), task.knowledgeBaseId(), token));
    }

    public boolean renew(ReleaseTaskLease lease, java.time.Duration duration) {
        return mapper.renew(
                lease.taskId(), lease.leaseToken(), LocalDateTime.now().plus(duration)) == 1;
    }

    public boolean complete(ReleaseTaskLease lease) {
        return mapper.complete(lease.taskId(), lease.leaseToken()) == 1;
    }

    /** 返回 true 表示任务已耗尽重试并进入 FAILED。 */
    public boolean retryOrFail(ReleaseTaskLease lease, String errorCode) {
        ReleaseTask task = mapper.find(lease.taskId());
        if (task == null || !lease.leaseToken().equals(task.leaseToken())) {
            return false;
        }
        int nextAttempt = task.retryCount() + 1;
        boolean exhausted = nextAttempt >= properties.getMaxRetries();
        long multiplier = 1L << Math.min(task.retryCount(), 8);
        String message = errorCode.length() > 500 ? errorCode.substring(0, 500) : errorCode;
        int updated = mapper.retryOrFail(
                lease.taskId(), lease.leaseToken(), exhausted ? "FAILED" : "RETRY",
                LocalDateTime.now().plus(properties.getRetryBaseDelay().multipliedBy(multiplier)),
                errorCode, message);
        return updated == 1 && exhausted;
    }
}
