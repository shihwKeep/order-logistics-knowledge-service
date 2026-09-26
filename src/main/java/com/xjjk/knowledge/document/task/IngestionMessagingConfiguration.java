package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.publication.release.ReleaseProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnExpression("${knowledge.document.ingestion.enabled:false} || ${knowledge.release.enabled:false}")
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
    Binding ingestionBinding(
            @Qualifier("ingestionQueue") Queue ingestionQueue,
            DirectExchange ingestionExchange,
            IngestionProperties properties) {
        return BindingBuilder.bind(ingestionQueue).to(ingestionExchange).with(properties.getRoutingKey());
    }

    @Bean
    Queue releaseQueue(ReleaseProperties properties) {
        return new Queue(properties.getQueue(), true);
    }

    @Bean
    Binding releaseBinding(
            @Qualifier("releaseQueue") Queue releaseQueue,
            DirectExchange ingestionExchange,
            ReleaseProperties properties) {
        return BindingBuilder.bind(releaseQueue).to(ingestionExchange).with(properties.getRoutingKey());
    }
}
