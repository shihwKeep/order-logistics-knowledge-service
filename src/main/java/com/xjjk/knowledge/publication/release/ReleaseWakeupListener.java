package com.xjjk.knowledge.publication.release;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RabbitMQ 只负责低延迟唤醒；重复消息由数据库任务租约自然去重。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.release", name = "enabled", havingValue = "true")
public class ReleaseWakeupListener {
    private final ReleaseWorker worker;

    public ReleaseWakeupListener(ReleaseWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(queues = "${knowledge.release.queue:knowledge.release}")
    public void onWakeup(String taskId) {
        worker.process(Long.parseLong(taskId));
    }
}
