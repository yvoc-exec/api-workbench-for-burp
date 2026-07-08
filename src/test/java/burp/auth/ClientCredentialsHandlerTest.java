package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.testsupport.OAuth2HttpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientCredentialsHandlerTest {
    @AfterEach
    void tearDown() {
        TokenStore.clearAll();
    }

    @Test
    void nullApiUsesTestOnlyJdkPathAndParsesToken() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                OAuth2HttpTestSupport.ResponseSpec.json(200, """
                        {"access_token":"jdk-test-access","refresh_token":"refresh","expires_in":15}
                        """))) {
            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(config(server.url("/token")), null);

            assertThat(entry.accessToken).isEqualTo("jdk-test-access");
            assertThat(entry.refreshToken).isEqualTo("refresh");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void nonNullApiUsesMontoyaNetworking() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                OAuth2HttpTestSupport.ResponseSpec.json(200, """
                        {"access_token":"burp-access","expires_in":30}
                        """))) {
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);

            TokenStore.TokenEntry entry = new ClientCredentialsHandler().execute(config(server.url("/token")), api);

            assertThat(entry.accessToken).isEqualTo("burp-access");
            verify(api.http()).sendRequest(any(HttpRequest.class));
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void nonNullApiMontoyaFailureDoesNotFallbackToJdk() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) ->
                OAuth2HttpTestSupport.ResponseSpec.json(200, """
                        {"access_token":"should-not-be-used"}
                        """))) {
            MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
            when(api.http().sendRequest(any(HttpRequest.class)))
                    .thenThrow(new NullPointerException("ObjectFactoryLocator.FACTORY unavailable"));

            assertThatThrownBy(() -> new ClientCredentialsHandler().execute(config(server.url("/token")), api))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("OAuth2 token request failed via Burp networking")
                    .hasMessageContaining("ObjectFactoryLocator.FACTORY unavailable");
            assertThat(server.requestCount()).isZero();
        }
    }

    private static OAuth2Config config(String tokenUrl) {
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
