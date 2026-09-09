package com.xjjk.knowledge.document.task;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 事务 Outbox 在数据库提交后投递；发送失败不影响文档登记，可由下轮继续重试。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.document.ingestion", name = "enabled", havingValue = "true")
public class OutboxPublisher {
    private final OutboxMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final IngestionProperties properties;

    public OutboxPublisher(OutboxMapper mapper, RabbitTemplate rabbitTemplate, IngestionProperties properties) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.document.ingestion.outbox-delay:2000}")
    public void publishPending() {
        for (OutboxEvent event : mapper.findPending(properties.getScanBatchSize())) {
            try {
                rabbitTemplate.convertAndSend(
                        properties.getExchange(), properties.getRoutingKey(), Long.toString(event.taskId()));
                mapper.markPublished(event.id());
            } catch (RuntimeException exception) {
                mapper.markRetry(event.id());
            }
        }
    }
}
