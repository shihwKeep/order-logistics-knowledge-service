package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.retrieval.web.InternalRequestVerifier;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexOperation;
import com.xjjk.knowledge.usermemory.domain.MemoryRecallCandidate;
import com.xjjk.knowledge.usermemory.domain.MemoryRetrievalResult;
import com.xjjk.knowledge.usermemory.service.UserMemoryIndexService;
import com.xjjk.knowledge.usermemory.service.UserMemoryRetrievalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalUserMemoryControllerTest {

    @Test
    void verifiesExactPayloadAndNeverReturnsMemoryContent() {
        InternalRequestVerifier verifier = mock(InternalRequestVerifier.class);
        UserMemoryIndexService indexService = mock(UserMemoryIndexService.class);
        UserMemoryRetrievalService retrievalService = mock(UserMemoryRetrievalService.class);
        InternalUserMemoryController controller = new InternalUserMemoryController(
                verifier, indexService, retrievalService);
        IndexMemoryEventRequest indexRequest = new IndexMemoryEventRequest(
                "event-1", MemoryIndexOperation.UPSERT, 3, "m-1", 2,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "用户常用工作范围是 Java 开发", 0.95,
                Instant.parse("2027-03-11T00:00:00Z"));

        controller.index(1, 74680, 1000, "nonce-1", "a".repeat(64), indexRequest);

        verify(verifier).verifySignedPayload(
                InternalUserMemoryController.INDEX_PATH,
                1, 74680, 1000, "nonce-1", "a".repeat(64),
                indexRequest.payloadDigest());
        verify(indexService).apply(
                MemoryIndexOperation.UPSERT, indexRequest.toDocument(1, 74680));

        RetrieveUserMemoryRequest retrieve = new RetrieveUserMemoryRequest(
                "我使用什么编程语言", 3);
        when(retrievalService.retrieve(1, 74680, 3, retrieve.query()))
                .thenReturn(new MemoryRetrievalResult(
                        List.of(new MemoryRecallCandidate(
                                "m-1", 2, 0.91, 1, Set.of("VECTOR"))),
                        "v1", "NONE", "OK"));
        var response = controller.retrieve(
                1, 74680, 1001, "nonce-2", "b".repeat(64), retrieve);

        assertThat(response.data().candidates()).singleElement()
                .extracting(MemoryCandidateResponse::memoryId).isEqualTo("m-1");
        assertThat(response.toString()).doesNotContain("Java 开发");
    }
}
