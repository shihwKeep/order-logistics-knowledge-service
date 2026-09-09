package com.xjjk.knowledge.retrieval.web;

import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.retrieval.service.HybridRetrievalService;
import com.xjjk.knowledge.retrieval.web.dto.RetrieveKnowledgeRequest;
import com.xjjk.knowledge.retrieval.web.dto.RetrieveKnowledgeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 供 Agent 服务调用的已发布知识检索接口。 */
@RestController
@RequestMapping("/api/v1/internal/knowledge")
public class InternalRetrievalController {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final HybridRetrievalService service;
    private final InternalRequestVerifier verifier;

    public InternalRetrievalController(
            HybridRetrievalService service, InternalRequestVerifier verifier) {
        this.service = service;
        this.verifier = verifier;
    }

    @PostMapping("/retrieve")
    public ApiResponse<RetrieveKnowledgeResponse> retrieve(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @Valid @RequestBody RetrieveKnowledgeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        verifier.verify(
                tenantId, userId, timestamp, nonce, signature,
                body.question(), body.knowledgeBaseIds());
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(RetrieveKnowledgeResponse.from(service.retrieve(
                tenantId, userId, requestId, body.question(), body.knowledgeBaseIds())));
    }

    private String prepareRequestId(HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied == null || supplied.isBlank()
                || supplied.length() > MAX_REQUEST_ID_LENGTH
                ? UUID.randomUUID().toString() : supplied;
        request.setAttribute("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return requestId;
    }
}
