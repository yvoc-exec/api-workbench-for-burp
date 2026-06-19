package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.testsupport.OAuth2HttpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2GrantHandlerIntegrationTest {

    @AfterEach
    void tearDown() {
        TokenStore.clearAll();
    }

    @Test
    void clientCredentialsUsesBodyAuthAndParsesTokenMetadata() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.path).isEqualTo("/token");
            assertThat(request.headerValue("Content-Type")).contains("application/x-www-form-urlencoded");
            assertThat(request.formParams())
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "demo-client")
                    .containsEntry("client_secret", "demo-secret")
                    .containsEntry("scope", "scope:read");
            return OAuth2HttpTestSupport.ResponseSpec.json(200,
                    """
                    {"access_token":"access-123","refresh_token":"refresh-123","token_type":"DPoP","scope":"scope:read","expires_in":120}
                    """);
        })) {
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(clientCredentialsConfig(server.url("/token")), api);

            assertThat(entry.accessToken).isEqualTo("access-123");
            assertThat(entry.refreshToken).isEqualTo("refresh-123");
            assertThat(entry.tokenType).isEqualTo("DPoP");
            assertThat(entry.scope).isEqualTo("scope:read");
            assertThat(entry.expiresAt - entry.acquiredAt).isBetween(119_000L, 121_000L);
        }
    }

    @Test
    void clientCredentialsUsesBasicAuthAndDefaultsMissingFields() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.headerValue("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                    .encodeToString("demo-client:demo-secret".getBytes(StandardCharsets.UTF_8)));
            assertThat(request.formParams())
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "demo-client")
                    .doesNotContainKey("client_secret");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"basic-access"}
                    """);
        })) {
            OAuth2Config config = clientCredentialsConfig(server.url("/token"));
            config.clientAuth = "basic";
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(config, api);

            assertThat(entry.accessToken).isEqualTo("basic-access");
            assertThat(entry.refreshToken).isNull();
            assertThat(entry.tokenType).isEqualTo("Bearer");
            assertThat(entry.expiresAt - entry.acquiredAt).isBetween(3_599_000L, 3_601_000L);
        }
    }

    @Test
    void clientCredentialsAllowsClientAuthNoneWithoutSecret() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.headerValue("Authorization")).isNull();
            assertThat(request.formParams())
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "demo-client")
                    .doesNotContainKey("client_secret");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"public-access","expires_in":30}
                    """);
        })) {
            OAuth2Config config = clientCredentialsConfig(server.url("/token"));
            config.clientSecret = null;
            config.clientAuth = "none";
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(config, api);

            assertThat(entry.accessToken).isEqualTo("public-access");
            assertThat(entry.expiresAt - entry.acquiredAt).isBetween(29_000L, 31_000L);
        }
    }

    @Test
    void passwordGrantSendsCredentialsAndScope() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "password")
                    .containsEntry("client_id", "demo-client")
                    .containsEntry("client_secret", "demo-secret")
                    .containsEntry("username", "alice")
                    .containsEntry("password", "wonderland")
                    .containsEntry("scope", "scope:read");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"password-access","expires_in":90}
                    """);
        })) {
            OAuth2Config config = clientCredentialsConfig(server.url("/token"));
            config.grantType = OAuth2Config.GrantType.PASSWORD;
            config.username = "alice";
            config.password = "wonderland";
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new PasswordGrantHandler().execute(config, api);

            assertThat(entry.accessToken).isEqualTo("password-access");
            assertThat(entry.expiresAt - entry.acquiredAt).isBetween(89_000L, 91_000L);
        }
    }

    @Test
    void refreshTokenGrantSendsRefreshTokenAndSupportsRotation() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "refresh_token")
                    .containsEntry("client_id", "demo-client")
                    .containsEntry("client_secret", "demo-secret")
                    .containsEntry("refresh_token", "stale-refresh");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"fresh-access","refresh_token":"rotated-refresh","expires_in":60}
                    """);
        })) {
            OAuth2Config config = clientCredentialsConfig(server.url("/token"));
            config.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
            config.refreshToken = "stale-refresh";
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new RefreshTokenHandler().execute(config, api);

            assertThat(entry.accessToken).isEqualTo("fresh-access");
            assertThat(entry.refreshToken).isEqualTo("rotated-refresh");
        }
    }

    @Test
    void tokenEndpointErrorPayloadsSurfaceAcrossCommonFailureStatuses() throws Exception {
        int[] statuses = {400, 401, 429, 500};
        for (int status : statuses) {
            try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                    OAuth2HttpTestSupport.ResponseSpec.json(status,
                            """
                            {"error":"invalid_client","error_description":"bad credentials"}
                            """))) {
                MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

                assertThatThrownBy(() -> new ClientCredentialsHandler().execute(clientCredentialsConfig(server.url("/token")), api))
                        .isInstanceOf(Exception.class)
                        .hasMessage("OAuth2 error: invalid_client - bad credentials");
            }
        }
    }

    @Test
    void malformedJsonAndMissingAccessTokenFailClosed() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer malformed = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                OAuth2HttpTestSupport.ResponseSpec.plain(200, "not-json"))) {
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(malformed);

            assertThatThrownBy(() -> new ClientCredentialsHandler().execute(clientCredentialsConfig(malformed.url("/token")), api))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("non-JSON response")
                    .hasMessageContaining("status: 200");
        }

        try (OAuth2HttpTestSupport.TokenEndpointServer missingToken = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                OAuth2HttpTestSupport.ResponseSpec.json(200, """
                        {"token_type":"Bearer","expires_in":60}
                        """))) {
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(missingToken);

            assertThatThrownBy(() -> new ClientCredentialsHandler().execute(clientCredentialsConfig(missingToken.url("/token")), api))
                    .isInstanceOf(Exception.class)
                    .hasMessage("No access_token in response");
        }
    }

    @Test
    void redirectResponseIsNotFollowedSilently() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                new OAuth2HttpTestSupport.ResponseSpec(302, "",
                        Map.of("Content-Type", "text/html; charset=utf-8", "Location", "/redirected")))) {
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            assertThatThrownBy(() -> new ClientCredentialsHandler().execute(clientCredentialsConfig(server.url("/token")), api))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("non-JSON response")
                    .hasMessageContaining("status: 302");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private static OAuth2Config clientCredentialsConfig(String tokenUrl) {
        OAuth2Config config = new OAuth2Config();
        config.grantType = OAuth2Config.GrantType.CLIENT_CREDENTIALS;
        config.tokenUrl = tokenUrl;
        config.clientId = "demo-client";
        config.clientSecret = "demo-secret";
        config.scope = "scope:read";
        config.clientAuth = "body";
        return config;
    }
}
