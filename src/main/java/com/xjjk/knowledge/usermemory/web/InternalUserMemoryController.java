package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.retrieval.web.InternalRequestVerifier;
import com.xjjk.knowledge.usermemory.service.UserMemoryIndexService;
import com.xjjk.knowledge.usermemory.service.UserMemoryRetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅供 Agent Server 使用的用户记忆索引与候选召回接口。 */
@RestController
@RequestMapping("/api/v1/internal/user-memories")
public class InternalUserMemoryController {
    public static final String INDEX_PATH =
            "/api/v1/internal/user-memories/index-events";
    public static final String RETRIEVE_PATH =
            "/api/v1/internal/user-memories/retrieve";

    private final InternalRequestVerifier verifier;
    private final UserMemoryIndexService indexService;
    private final UserMemoryRetrievalService retrievalService;

    public InternalUserMemoryController(
            InternalRequestVerifier verifier,
            UserMemoryIndexService indexService,
            UserMemoryRetrievalService retrievalService) {
        this.verifier = verifier;
        this.indexService = indexService;
        this.retrievalService = retrievalService;
    }

    @PostMapping("/index-events")
    public ApiResponse<IndexMemoryEventResponse> index(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @RequestBody IndexMemoryEventRequest body) {
        verifier.verifySignedPayload(
                INDEX_PATH, tenantId, userId, timestamp, nonce,
                signature, body.payloadDigest());
        switch (body.operation()) {
            case UPSERT -> indexService.apply(
                    body.operation(), body.toDocument(tenantId, userId));
            case DELETE -> indexService.delete(
                    tenantId, userId, body.memoryGeneration(), body.memoryId());
            case DELETE_EXPLICIT_SCOPE -> indexService.deleteExplicitScope(
                    tenantId, userId, body.memoryGeneration());
            case CLEAR_GENERATION -> indexService.clearGeneration(
                    tenantId, userId, body.memoryGeneration());
        }
        return ApiResponse.success(new IndexMemoryEventResponse(
                body.eventId(), "APPLIED"));
    }

    @PostMapping("/retrieve")
    public ApiResponse<RetrieveUserMemoryResponse> retrieve(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @RequestBody RetrieveUserMemoryRequest body) {
        verifier.verifySignedPayload(
                RETRIEVE_PATH, tenantId, userId, timestamp, nonce,
                signature, body.payloadDigest());
        return ApiResponse.success(RetrieveUserMemoryResponse.from(
                retrievalService.retrieve(
                        tenantId, userId, body.memoryGeneration(), body.query())));
    }
}
