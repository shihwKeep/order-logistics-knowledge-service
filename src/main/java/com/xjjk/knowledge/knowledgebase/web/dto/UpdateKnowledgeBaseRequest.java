package com.xjjk.knowledge.knowledgebase.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 更新知识库请求，expectedVersion 用于乐观锁。 */
public record UpdateKnowledgeBaseRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @PositiveOrZero int expectedVersion
) {
}
