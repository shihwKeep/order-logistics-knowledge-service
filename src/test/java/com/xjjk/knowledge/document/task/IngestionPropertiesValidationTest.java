package com.xjjk.knowledge.document.task;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionPropertiesValidationTest {

    @Test
    void rejectsNonPositiveConcurrencyLimit() {
        IngestionProperties properties = new IngestionProperties();
        properties.setMaxConcurrentTasks(0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("maxConcurrentTasks");
        }
    }
}
