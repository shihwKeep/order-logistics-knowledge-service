package com.xjjk.knowledge.usermemory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMemoryIndexPropertiesTest {

    @Test
    void acceptsProductionDefaults() {
        UserMemoryIndexProperties properties = new UserMemoryIndexProperties();
        properties.validate();
    }

    @Test
    void rejectsUnsafeIndexNamesAndRetrievalLimits() {
        UserMemoryIndexProperties properties = new UserMemoryIndexProperties();
        properties.getElasticsearch().setIndexAlias("knowledge-published");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class);

        properties = new UserMemoryIndexProperties();
        properties.getRetrieval().setFinalTopK(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
