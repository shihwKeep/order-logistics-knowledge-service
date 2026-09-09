package com.xjjk.knowledge.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** `/authorizationcenter/user/current` 专用响应包，成功码固定为 1000。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxCurrentUserEnvelope(
        @JsonAlias({"Code", "code"}) Integer code,
        @JsonAlias({"Msg", "msg"}) String message,
        @JsonAlias({"Data", "data"}) String data
) {
}
