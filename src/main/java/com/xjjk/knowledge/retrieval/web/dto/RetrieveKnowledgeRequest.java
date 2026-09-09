package com.xjjk.knowledge.retrieval.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 内部接口和管理端诊断接口共用的检索请求。 */
public record RetrieveKnowledgeRequest(
        @NotBlank @Size(max = 2000) String question,
        @Size(max = 50) List<@Positive Long> knowledgeBaseIds) {
    public RetrieveKnowledgeRequest {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }
}
