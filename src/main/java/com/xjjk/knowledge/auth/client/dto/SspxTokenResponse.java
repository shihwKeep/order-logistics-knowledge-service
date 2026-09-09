package com.xjjk.knowledge.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** SSPX OAuth2 令牌响应，仅在知识库服务内部使用。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("expires_time") Long expiresTime,
        @JsonAlias({"code", "Code"}) Integer code,
        @JsonAlias({"msg", "Msg"}) String message
) {
}
