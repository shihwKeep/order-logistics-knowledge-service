package com.xjjk.knowledge.document.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionBulkheadTest {

    @Test
    void rejectsExcessWorkAndReleasesPermitForTheNextTask() {
        IngestionProperties properties = new IngestionProperties();
        properties.setMaxConcurrentTasks(1);
        IngestionBulkhead bulkhead = new IngestionBulkhead(properties);

        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isFalse();

        bulkhead.release();

        assertThat(bulkhead.tryAcquire()).isTrue();
        bulkhead.release();
    }
}
