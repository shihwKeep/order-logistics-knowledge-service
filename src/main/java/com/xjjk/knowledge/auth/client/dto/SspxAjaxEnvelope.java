package com.xjjk.knowledge.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** SSPX AjaxJson 角色接口响应包，成功码固定为 200。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxAjaxEnvelope(
        @JsonAlias({"code", "Code"}) Integer code,
        @JsonAlias({"msg", "Msg"}) String message,
        @JsonAlias({"data", "Data"}) List<SspxRolePayload> data
) {
}
