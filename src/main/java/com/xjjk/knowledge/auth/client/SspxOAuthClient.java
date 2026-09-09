package com.xjjk.knowledge.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.client.dto.SspxTokenResponse;
import com.xjjk.knowledge.auth.config.SspxProperties;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

/**
 * SSPX OAuth2 密码模式客户端。
 * 密码、客户端密钥以及返回的令牌均不得写入日志。
 */
@Component
public class SspxOAuthClient {

    private final RestClient restClient;
    private final SspxProperties properties;
    private final ObjectMapper objectMapper;

    public SspxOAuthClient(
            RestClient.Builder builder,
            SspxProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SspxTokenResponse passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("scope", "profile");
        form.add("username", username);
        form.add("password", password);

        try {
            SspxTokenResponse response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        if (status >= 500) {
                            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
                        }
                        if (status >= 400) {
                            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
                        }
                        return readTokenResponse(httpResponse.getBody());
                    });
            if (response == null || isBlank(response.accessToken())) {
                throw new BusinessException(ApiErrorCode.AUTH_INVALID);
            }
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }
    }

    private SspxTokenResponse readTokenResponse(java.io.InputStream body) {
        try {
            return objectMapper.readValue(body, SspxTokenResponse.class);
        } catch (IOException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
