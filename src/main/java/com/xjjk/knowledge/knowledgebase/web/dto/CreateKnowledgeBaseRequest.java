package com.xjjk.knowledge.knowledgebase.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建知识库请求，长度超限直接拒绝，绝不静默截断。 */
public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {
}
