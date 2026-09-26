package com.xjjk.knowledge.publication.cleanup;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class DerivedCleanupRepository {
    private final DerivedCleanupMapper mapper;

    public DerivedCleanupRepository(DerivedCleanupMapper mapper) {
        this.mapper = mapper;
    }

    public List<Long> findDueIds(int limit) {
        return mapper.findDueIds(limit);
    }

    public Optional<DerivedCleanupTask> claim(
            long id, String workerId, Duration leaseDuration) {
        String token = UUID.randomUUID().toString();
        if (mapper.claim(id, token, workerId, LocalDateTime.now().plus(leaseDuration)) != 1) {
            return Optional.empty();
        }
        DerivedCleanupTask task = mapper.find(id);
        if (task == null || !token.equals(task.leaseToken())) {
            return Optional.empty();
        }
        return Optional.of(task);
    }

    public boolean isReferenced(DerivedCleanupTask task) {
        return mapper.isReferenced(task);
    }

    public boolean completeOwned(long id, String leaseToken) {
        return mapper.completeOwned(id, leaseToken) == 1;
    }

    public boolean retryOwned(
            DerivedCleanupTask task, String errorCode, int maxRetries, Duration baseDelay) {
        int nextAttempt = task.retryCount() + 1;
        String status = nextAttempt >= maxRetries ? "FAILED" : "RETRY";
        long multiplier = 1L << Math.min(task.retryCount(), 8);
        LocalDateTime nextRunAt = LocalDateTime.now().plus(baseDelay.multipliedBy(multiplier));
        return mapper.retryOwned(
                task.id(), task.leaseToken(), status, nextRunAt, errorCode) == 1;
    }
}
