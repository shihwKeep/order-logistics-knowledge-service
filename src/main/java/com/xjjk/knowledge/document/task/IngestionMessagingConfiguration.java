package com.xjjk.knowledge.document.task;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "knowledge.document.ingestion", name = "enabled", havingValue = "true")
public class IngestionMessagingConfiguration {
    @Bean
    DirectExchange ingestionExchange(IngestionProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    Queue ingestionQueue(IngestionProperties properties) {
        return new Queue(properties.getQueue(), true);
    }

    @Bean
    Binding ingestionBinding(Queue ingestionQueue, DirectExchange ingestionExchange, IngestionProperties properties) {
        return BindingBuilder.bind(ingestionQueue).to(ingestionExchange).with(properties.getRoutingKey());
    }
}
