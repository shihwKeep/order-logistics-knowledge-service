package com.xjjk.knowledge.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.client.dto.SspxCurrentUserPayload;
import com.xjjk.knowledge.auth.client.dto.SspxRolePayload;
import com.xjjk.knowledge.auth.client.dto.SspxTokenResponse;
import com.xjjk.knowledge.auth.config.SspxProperties;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SspxClientTest {

    private MockRestServiceServer server;
    private SspxOAuthClient oauthClient;
    private SspxIdentityClient identityClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SspxProperties properties = new SspxProperties(
                "http://localhost:9092", "100006", "test-secret", 444L);
        ObjectMapper objectMapper = new ObjectMapper();
        oauthClient = new SspxOAuthClient(builder, properties, objectMapper);
        identityClient = new SspxIdentityClient(builder, properties, objectMapper);
    }

    @Test
    void sendsPasswordGrantAndReadsToken() {
        server.expect(requestTo("http://localhost:9092/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(allOf(
                        containsString("grant_type=password"),
                        containsString("client_id=100006"),
                        containsString("client_secret=test-secret"),
                        containsString("scope=profile"),
                        containsString("username=74680"),
                        containsString("password=secret")
                )))
                .andRespond(withSuccess("""
                        {
                          "access_token":"access-token",
                          "refresh_token":"refresh-token",
                          "token_type":"Bearer",
                          "expires_in":3600
                        }
                        """, MediaType.APPLICATION_JSON));

        SspxTokenResponse response = oauthClient.passwordGrant("74680", "secret");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        server.verify();
    }

    @Test
    void decodesCurrentUserAndReturnsOnlyValidKnowledgeRoles() {
        String currentUserHex = hex("""
                {"Id":10567,"Account":"74680","Name":"石海文","OrgId":1061,"CompanyId":1}
                """);
        server.expect(requestTo("http://localhost:9092/authorizationcenter/user/current"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"Code":1000,"Msg":"success","Data":"%s"}
                        """.formatted(currentUserHex), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://localhost:9092/authorizationcenter/user/roles?applicationId=444"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess("""
                        {
                          "code":200,
                          "msg":"ok",
                          "data":[
                            {"applicationId":444,"companyId":1,"code":null,"status":1,"isDeleted":false},
                            {"applicationId":444,"companyId":1,"code":"KNOWLEDGE_ADMIN","status":1,"isDeleted":false},
                            {"applicationId":999,"companyId":1,"code":"KNOWLEDGE_SUPER_ADMIN","status":1,"isDeleted":false},
                            {"applicationId":444,"companyId":1,"code":"OTHER_ROLE","status":1,"isDeleted":false}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SspxCurrentUserPayload currentUser = identityClient.currentUser("access-token");
        List<SspxRolePayload> roles = identityClient.knowledgeRoles("access-token");

        assertThat(currentUser.id()).isEqualTo(10567L);
        assertThat(currentUser.companyId()).isEqualTo(1L);
        assertThat(roles).extracting(SspxRolePayload::code)
                .containsExactly("KNOWLEDGE_ADMIN");
        server.verify();
    }

    @Test
    void rejectsMalformedCurrentUserHex() {
        server.expect(requestTo("http://localhost:9092/authorizationcenter/user/current"))
                .andRespond(withSuccess(
                        "{\"Code\":1000,\"Data\":\"not-hex\"}", MediaType.APPLICATION_JSON));

        assertAuthError(() -> identityClient.currentUser("access-token"), ApiErrorCode.AUTH_INVALID);
    }

    @Test
    void rejectsNonSuccessCurrentUserEnvelope() {
        server.expect(requestTo("http://localhost:9092/authorizationcenter/user/current"))
                .andRespond(withSuccess(
                        "{\"Code\":500,\"Msg\":\"error\",\"Data\":null}", MediaType.APPLICATION_JSON));

        assertAuthError(() -> identityClient.currentUser("access-token"), ApiErrorCode.AUTH_INVALID);
    }

    @Test
    void rejectsCurrentUserWithMissingRequiredFields() {
        server.expect(requestTo("http://localhost:9092/authorizationcenter/user/current"))
                .andRespond(withSuccess("""
                        {"Code":1000,"Data":"%s"}
                        """.formatted(hex("{\"Id\":10567}")), MediaType.APPLICATION_JSON));

        assertAuthError(() -> identityClient.currentUser("access-token"), ApiErrorCode.AUTH_INVALID);
    }

    @Test
    void mapsOAuth4xxToInvalidCredentialsAnd5xxToUnavailable() {
        server.expect(requestTo("http://localhost:9092/oauth2/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertAuthError(() -> oauthClient.passwordGrant("74680", "wrong"), ApiErrorCode.AUTH_INVALID);

        server.reset();
        server.expect(requestTo("http://localhost:9092/oauth2/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertAuthError(() -> oauthClient.passwordGrant("74680", "secret"),
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
    }

    @Test
    void rejectsNonSuccessRoleEnvelopeAndMapsRole5xxToUnavailable() {
        server.expect(requestTo(
                        "http://localhost:9092/authorizationcenter/user/roles?applicationId=444"))
                .andRespond(withSuccess(
                        "{\"code\":500,\"msg\":\"error\"}", MediaType.APPLICATION_JSON));
        assertAuthError(
                () -> identityClient.knowledgeRoles("access-token"),
                ApiErrorCode.AUTH_INVALID);

        server.reset();
        server.expect(requestTo(
                        "http://localhost:9092/authorizationcenter/user/roles?applicationId=444"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));
        assertAuthError(
                () -> identityClient.knowledgeRoles("access-token"),
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
    }

    private static String hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertAuthError(Runnable action, ApiErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
