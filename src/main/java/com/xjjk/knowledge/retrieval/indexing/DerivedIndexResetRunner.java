package com.xjjk.knowledge.retrieval.indexing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "knowledge.maintenance",
        name = "reset-derived-indexes-on-startup", havingValue = "true")
public class DerivedIndexResetRunner implements ApplicationRunner {
    private final DerivedIndexResetService service;
    private final String confirmation;

    public DerivedIndexResetRunner(DerivedIndexResetService service,
            @Value("${knowledge.maintenance.reset-confirmation:}") String confirmation) {
        this.service = service;
        this.confirmation = confirmation;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.reset(confirmation);
    }
}
