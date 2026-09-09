package com.xjjk.knowledge.document.task;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 后台入库进程内舱壁。
 *
 * <p>MQ 与兜底扫描器可能同时唤醒任务，所以必须在领取数据库租约之前限并发。
 * 未拿到许可的任务不会改变数据库状态，稍后仍可由扫描器重新发现。</p>
 */
@Component
public class IngestionBulkhead {
    private final Semaphore permits;

    public IngestionBulkhead(IngestionProperties properties) {
        this.permits = new Semaphore(properties.getMaxConcurrentTasks(), true);
    }

    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }
}
