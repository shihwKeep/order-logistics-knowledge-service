package com.xjjk.knowledge.retrieval.indexing;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexRecoveryRunnerTest {

    @Test
    void invokesFullRecoveryWhenConditionalRunnerExists() throws Exception {
        IndexRecoveryService service = mock(IndexRecoveryService.class);
        when(service.rebuildAll()).thenReturn(new IndexRecoveryResult(2, 3));

        new IndexRecoveryRunner(service).run(null);

        verify(service).rebuildAll();
    }
}
