package com.xjjk.knowledge.document.task;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MQ 消息只携带任务 id；重复消息会被数据库领取条件自然去重。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.document.ingestion", name = "enabled", havingValue = "true")
public class IngestionWakeupListener {
    private final IngestionWorker worker;

    public IngestionWakeupListener(IngestionWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(queues = "${knowledge.document.ingestion.queue:knowledge.document.ingestion}")
    public void onWakeup(long taskId) {
        worker.process(taskId);
    }
}
