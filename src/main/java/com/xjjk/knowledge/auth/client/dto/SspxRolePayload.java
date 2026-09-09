package com.xjjk.knowledge.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 只保留知识库鉴权需要的 SSPX 角色字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxRolePayload(
        Long applicationId,
        Long companyId,
        String code,
        Integer status,
        Boolean isDeleted
) {
}
