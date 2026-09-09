package com.xjjk.knowledge.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** SSPX 当前用户的十六进制 JSON 解码结果。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxCurrentUserPayload(
        @JsonAlias({"Id", "id"}) Long id,
        @JsonAlias({"Account", "account"}) String account,
        @JsonAlias({"Name", "name"}) String name,
        @JsonAlias({"OrgId", "orgId"}) Long orgId,
        @JsonAlias({"CompanyId", "companyId"}) Long companyId
) {
}
