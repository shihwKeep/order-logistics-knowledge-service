package com.xjjk.knowledge.document.task;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** INDEX 长任务的租约心跳；续租失败后旧 Worker 不得继续提交 READY。 */
final class IngestionLeaseHeartbeat implements AutoCloseable {
    private final IngestionTaskRepository tasks;
    private final IngestionTaskLease lease;
    private final Duration leaseDuration;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final ScheduledExecutorService scheduler;

    IngestionLeaseHeartbeat(
            IngestionTaskRepository tasks, IngestionTaskLease lease, Duration leaseDuration) {
        this.tasks = tasks;
        this.lease = lease;
        this.leaseDuration = leaseDuration;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-index-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean start() {
        renew();
        if (!held.get()) {
            return false;
        }
        long intervalMillis = Math.max(10L, leaseDuration.toMillis() / 3L);
        scheduler.scheduleAtFixedRate(this::renew, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    boolean isHeld() {
        return held.get();
    }

    private void renew() {
        if (!held.get()) {
            return;
        }
        try {
            held.set(tasks.renew(lease.taskId(), lease.leaseToken(), leaseDuration));
        } catch (RuntimeException exception) {
            // 续租依赖异常时按失去租约处理，不能让旧 Worker 继续写 READY。
            held.set(false);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
