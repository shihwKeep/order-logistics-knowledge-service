package com.xjjk.knowledge.auth.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.client.dto.SspxAjaxEnvelope;
import com.xjjk.knowledge.auth.client.dto.SspxCurrentUserEnvelope;
import com.xjjk.knowledge.auth.client.dto.SspxCurrentUserPayload;
import com.xjjk.knowledge.auth.client.dto.SspxRolePayload;
import com.xjjk.knowledge.auth.config.SspxProperties;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** SSPX 当前用户与应用角色客户端。 */
@Component
public class SspxIdentityClient {

    private static final int CURRENT_USER_SUCCESS_CODE = 1000;
    private static final int AJAX_SUCCESS_CODE = 200;
    private static final Set<String> KNOWLEDGE_ROLE_CODES = Set.of(
            "KNOWLEDGE_ADMIN", "KNOWLEDGE_SUPER_ADMIN");

    private final RestClient restClient;
    private final SspxProperties properties;
    private final ObjectMapper objectMapper;

    public SspxIdentityClient(
            RestClient.Builder builder,
            SspxProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SspxCurrentUserPayload currentUser(String accessToken) {
        SspxCurrentUserEnvelope envelope = executeGet(
                "/authorizationcenter/user/current",
                accessToken,
                SspxCurrentUserEnvelope.class);
        if (envelope == null
                || !Integer.valueOf(CURRENT_USER_SUCCESS_CODE).equals(envelope.code())
                || isBlank(envelope.data())) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }

        SspxCurrentUserPayload payload = decodeCurrentUser(envelope.data());
        if (payload == null
                || payload.id() == null || payload.id() <= 0
                || isBlank(payload.account())
                || isBlank(payload.name())
                || payload.companyId() == null || payload.companyId() <= 0) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }
        return payload;
    }

    public List<SspxRolePayload> knowledgeRoles(String accessToken, long userId) {
        String uri = "/SysOpenUserRole/getUserRoles?applicationId="
                + properties.applicationId() + "&userId=" + userId;
        SspxAjaxEnvelope envelope = executeGet(uri, accessToken, SspxAjaxEnvelope.class);
        if (envelope == null
                || !Integer.valueOf(AJAX_SUCCESS_CODE).equals(envelope.code())
                || envelope.data() == null) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }

        // 角色只认应用 444 下启用、未删除的稳定英文 code，不依赖中文名称或历史角色 ID。
        return envelope.data().stream()
                .filter(this::isValidKnowledgeRole)
                .toList();
    }

    private boolean isValidKnowledgeRole(SspxRolePayload role) {
        return role != null
                && Long.valueOf(properties.applicationId()).equals(role.applicationId())
                && Integer.valueOf(1).equals(role.status())
                && !Boolean.TRUE.equals(role.isDeleted())
                && KNOWLEDGE_ROLE_CODES.contains(role.code());
    }

    private SspxCurrentUserPayload decodeCurrentUser(String encodedPayload) {
        try {
            byte[] jsonBytes = HexFormat.of().parseHex(encodedPayload);
            return objectMapper.readValue(
                    new String(jsonBytes, StandardCharsets.UTF_8),
                    SspxCurrentUserPayload.class);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }
    }

    private <T> T executeGet(String uri, String accessToken, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        if (status >= 500) {
                            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
                        }
                        if (status >= 400) {
                            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
                        }
                        try {
                            return objectMapper.readValue(httpResponse.getBody(), responseType);
                        } catch (IOException exception) {
                            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
                        }
                    });
        } catch (BusinessException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_INVALID);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
