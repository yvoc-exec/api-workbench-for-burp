package burp.auth;

import burp.testsupport.OAuth2HttpTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrantHandlerRequestCompositionTest {

    @Test
    void normalizeClientAuthModeKeepsOnlySupportedModes() {
        OAuth2Config config = baseConfig("http://127.0.0.1:1/token");

        assertThat(ClientCredentialsHandler.normalizeClientAuthMode(config)).isEqualTo("body");

        config.clientAuth = " prefer_basic ";
        assertThat(ClientCredentialsHandler.normalizeClientAuthMode(config)).isEqualTo("basic");
        assertThat(ClientCredentialsHandler.isBasicAuth(config)).isTrue();

        config.clientAuth = "none";
        assertThat(ClientCredentialsHandler.normalizeClientAuthMode(config)).isEqualTo("none");
        assertThat(ClientCredentialsHandler.isNoClientAuth(config)).isTrue();

        config.clientAuth = "unexpected-mode";
        assertThat(ClientCredentialsHandler.normalizeClientAuthMode(config)).isEqualTo("body");
        assertThat(ClientCredentialsHandler.isBodyClientSecret(config)).isTrue();
    }

    @Test
    void appendFormParamEncodesPairsAndAllowsNullValues() {
        StringBuilder body = new StringBuilder();

        ClientCredentialsHandler.appendFormParam(body, "grant type", "client credentials", true);
        ClientCredentialsHandler.appendFormParam(body, "scope", "read write", false);
        ClientCredentialsHandler.appendFormParam(body, "audience", null, false);

        assertThat(body.toString())
                .isEqualTo("grant+type=client+credentials&scope=read+write&audience");
    }

    @Test
    void validateBodySecretIfNeededRejectsMissingBodySecretOnlyForBodyMode() {
        OAuth2Config config = baseConfig("http://127.0.0.1:1/token");
        config.clientSecret = "";

        assertThatThrownBy(() -> ClientCredentialsHandler.validateBodySecretIfNeeded(config))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("oauth2_client_secret");

        config.clientAuth = "basic";
        assertThatCode(() -> ClientCredentialsHandler.validateBodySecretIfNeeded(config))
                .doesNotThrowAnyException();

        config.clientAuth = "none";
        assertThatCode(() -> ClientCredentialsHandler.validateBodySecretIfNeeded(config))
                .doesNotThrowAnyException();
    }

    @Test
    void clientCredentialsBodyModeBuildsExpectedJdkRequest() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "demo-client")
                    .containsEntry("client_secret", "demo-secret")
                    .containsEntry("scope", "scope:read");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"body-access","expires_in":45}
                    """);
        })) {
            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(baseConfig(server.url("/token")), null);

            assertThat(entry.accessToken).isEqualTo("body-access");
        }
    }

    @Test
    void clientCredentialsBasicModeBuildsExpectedJdkRequest() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.headerValue("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                    .encodeToString("demo-client:demo-secret".getBytes(StandardCharsets.UTF_8)));
            assertThat(request.formParams())
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "demo-client")
                    .doesNotContainKey("client_secret");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"basic-access","expires_in":30}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            config.clientAuth = "basic";

            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(config, null);

            assertThat(entry.accessToken).isEqualTo("basic-access");
        }
    }

    @Test
    void passwordGrantBuildsExpectedJdkRequest() throws Exception {
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
                    {"access_token":"password-access","expires_in":45}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            config.grantType = OAuth2Config.GrantType.PASSWORD;
            config.username = "alice";
            config.password = "wonderland";

            TokenStore.TokenEntry entry = new PasswordGrantHandler().execute(config, null);

            assertThat(entry.accessToken).isEqualTo("password-access");
        }
    }

    @Test
    void refreshTokenGrantBuildsExpectedJdkRequest() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "refresh_token")
                    .containsEntry("client_id", "demo-client")
                    .containsEntry("client_secret", "demo-secret")
                    .containsEntry("refresh_token", "refresh-123");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"refresh-access","refresh_token":"rotated-refresh","expires_in":45}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            config.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
            config.refreshToken = "refresh-123";

            TokenStore.TokenEntry entry = new RefreshTokenHandler().execute(config, null);

            assertThat(entry.accessToken).isEqualTo("refresh-access");
            assertThat(entry.refreshToken).isEqualTo("rotated-refresh");
        }
    }

    private static OAuth2Config baseConfig(String tokenUrl) {
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
