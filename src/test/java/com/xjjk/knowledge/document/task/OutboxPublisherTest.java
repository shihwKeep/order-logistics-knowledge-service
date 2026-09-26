package com.xjjk.knowledge.document.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.publication.release.ReleaseProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherTest {
    @Test
    void routesIngestionAndReleaseEventsToDifferentWakeupQueues() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        IngestionProperties ingestion = new IngestionProperties();
        ReleaseProperties release = new ReleaseProperties();
        when(mapper.findPending(20)).thenReturn(List.of(
                new OutboxEvent(1L, 11L, "DOCUMENT_INGESTION_REQUESTED", 0),
                new OutboxEvent(2L, 22L, "KNOWLEDGE_RELEASE_REQUESTED", 0)));
        OutboxPublisher publisher = new OutboxPublisher(mapper, rabbit, ingestion, release);

        publisher.publishPending();

        verify(rabbit).convertAndSend(
                ingestion.getExchange(), ingestion.getRoutingKey(), "11");
        verify(rabbit).convertAndSend(
                ingestion.getExchange(), release.getRoutingKey(), "22");
        verify(mapper).markPublished(1L);
        verify(mapper).markPublished(2L);
    }
}
