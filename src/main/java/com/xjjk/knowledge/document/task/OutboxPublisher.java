package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.publication.release.ReleaseProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 事务 Outbox 在数据库提交后投递；发送失败不影响文档登记，可由下轮继续重试。 */
@Component
@ConditionalOnExpression("${knowledge.document.ingestion.enabled:false} || ${knowledge.release.enabled:false}")
public class OutboxPublisher {
    private final OutboxMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final IngestionProperties properties;
    private final ReleaseProperties releaseProperties;

    public OutboxPublisher(
            OutboxMapper mapper,
            RabbitTemplate rabbitTemplate,
            IngestionProperties properties,
            ReleaseProperties releaseProperties) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.releaseProperties = releaseProperties;
    }

    @Scheduled(fixedDelayString = "${knowledge.document.ingestion.outbox-delay:2000}")
    public void publishPending() {
        for (OutboxEvent event : mapper.findPending(properties.getScanBatchSize())) {
            try {
                String routingKey = switch (event.eventType()) {
                    case "DOCUMENT_INGESTION_REQUESTED" -> properties.getRoutingKey();
                    case "KNOWLEDGE_RELEASE_REQUESTED" -> releaseProperties.getRoutingKey();
                    default -> throw new IllegalStateException("未知 Outbox 事件: " + event.eventType());
                };
                rabbitTemplate.convertAndSend(
                        properties.getExchange(), routingKey, Long.toString(event.taskId()));
                mapper.markPublished(event.id());
            } catch (RuntimeException exception) {
                mapper.markRetry(event.id());
            }
        }
    }
}
