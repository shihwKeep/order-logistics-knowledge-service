package com.xjjk.knowledge.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 管理台用户名密码登录请求。 */
public record LoginRequest(
        @NotBlank String account,
        @NotBlank String password
) {
}
