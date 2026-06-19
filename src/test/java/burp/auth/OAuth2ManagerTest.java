package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.testsupport.OAuth2HttpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ManagerTest {

    @AfterEach
    void tearDown() {
        TokenStore.clearAll();
    }

    @Test
    void getValidTokenReturnsStoredTokenWithoutNetworkWhenStillFresh() throws Exception {
        OAuth2Config config = baseConfig("http://127.0.0.1:1/token");
        TokenStore.TokenEntry entry = expiredEntry("cached-access", "cached-refresh");
        entry.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        TokenStore.store(TokenStore.makeKey(config), entry);

        OAuth2Manager manager = new OAuth2Manager(org.mockito.Mockito.mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        TokenStore.TokenEntry resolved = manager.getValidToken(config);

        assertThat(resolved).isSameAs(entry);
    }

    @Test
    void getValidTokenRefreshesExpiredEntryWithoutMutatingOriginalGrantType() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "refresh_token")
                    .containsEntry("refresh_token", "stale-refresh");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"fresh-access","refresh_token":"rotated-refresh","expires_in":45}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            TokenStore.store(TokenStore.makeKey(config), expiredEntry("stale-access", "stale-refresh"));
            MontoyaApi api = OAuth2HttpTestSupport.mockMontoyaApi(server);
            OAuth2Manager manager = new OAuth2Manager(api);

            TokenStore.TokenEntry refreshed = manager.getValidToken(config);

            assertThat(refreshed.accessToken).isEqualTo("fresh-access");
            assertThat(refreshed.refreshToken).isEqualTo("rotated-refresh");
            assertThat(config.grantType).isEqualTo(OAuth2Config.GrantType.CLIENT_CREDENTIALS);
            assertThat(TokenStore.get(TokenStore.makeKey(config)).accessToken).isEqualTo("fresh-access");
        }
    }

    @Test
    void refreshFailureFallsBackToOriginalGrantFlow() throws Exception {
        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            if (invocation == 1) {
                assertThat(request.formParams()).containsEntry("grant_type", "refresh_token");
                return OAuth2HttpTestSupport.ResponseSpec.json(400,
                        """
                        {"error":"invalid_grant","error_description":"refresh expired"}
                        """);
            }
            assertThat(invocation).isEqualTo(2);
            assertThat(request.formParams()).containsEntry("grant_type", "client_credentials");
            return OAuth2HttpTestSupport.ResponseSpec.json(200,
                    """
                    {"access_token":"reauth-access","expires_in":60}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            TokenStore.store(TokenStore.makeKey(config), expiredEntry("stale-access", "expired-refresh"));
            OAuth2Manager manager = new OAuth2Manager(OAuth2HttpTestSupport.mockMontoyaApi(server));

            TokenStore.TokenEntry reauthenticated = manager.getValidToken(config);

            assertThat(reauthenticated.accessToken).isEqualTo("reauth-access");
            assertThat(server.requestCount()).isEqualTo(2);
            List<OAuth2HttpTestSupport.RecordedRequest> requests = server.requests();
            assertThat(requests.get(0).formParams()).containsEntry("grant_type", "refresh_token");
            assertThat(requests.get(1).formParams()).containsEntry("grant_type", "client_credentials");
            assertThat(config.grantType).isEqualTo(OAuth2Config.GrantType.CLIENT_CREDENTIALS);
        }
    }

    @Test
    void concurrentRefreshRequestsShareSingleRefresh() throws Exception {
        CountDownLatch firstRequestArrived = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);

        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            firstRequestArrived.countDown();
            assertThat(releaseRefresh.await(5, TimeUnit.SECONDS)).isTrue();
            return OAuth2HttpTestSupport.ResponseSpec.json(200,
                    """
                    {"access_token":"shared-access","refresh_token":"shared-refresh","expires_in":120}
                    """);
        })) {
            OAuth2Config config = baseConfig(server.url("/token"));
            TokenStore.store(TokenStore.makeKey(config), expiredEntry("stale-access", "shared-refresh"));
            OAuth2Manager manager = new OAuth2Manager(OAuth2HttpTestSupport.mockMontoyaApi(server));

            var executor = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<TokenStore.TokenEntry> first = CompletableFuture.supplyAsync(() -> resolve(manager, config), executor);
                CompletableFuture<TokenStore.TokenEntry> second = CompletableFuture.supplyAsync(() -> resolve(manager, config), executor);

                assertThat(firstRequestArrived.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150L);
                assertThat(server.requestCount()).isEqualTo(1);
                releaseRefresh.countDown();

                TokenStore.TokenEntry firstEntry = first.get(5, TimeUnit.SECONDS);
                TokenStore.TokenEntry secondEntry = second.get(5, TimeUnit.SECONDS);

                assertThat(firstEntry.accessToken).isEqualTo("shared-access");
                assertThat(secondEntry.accessToken).isEqualTo("shared-access");
                assertThat(server.requestCount()).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void getValidTokenDoesNotReusePasswordGrantTokensAcrossDifferentUsernames() throws Exception {
        OAuth2Config alice = passwordConfig("http://127.0.0.1:1/token", "alice");
        TokenStore.TokenEntry cachedAlice = new TokenStore.TokenEntry();
        cachedAlice.accessToken = "alice-access";
        cachedAlice.tokenType = "Bearer";
        cachedAlice.acquiredAt = System.currentTimeMillis();
        cachedAlice.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        TokenStore.store(TokenStore.makeKey(alice), cachedAlice);

        try (OAuth2HttpTestSupport.TokenEndpointServer server = new OAuth2HttpTestSupport.TokenEndpointServer((request, invocation) -> {
            assertThat(invocation).isEqualTo(1);
            assertThat(request.formParams())
                    .containsEntry("grant_type", "password")
                    .containsEntry("username", "bob")
                    .containsEntry("password", "wonderland");
            return OAuth2HttpTestSupport.ResponseSpec.json(200, """
                    {"access_token":"bob-access","expires_in":60}
                    """);
        })) {
            OAuth2Config bob = passwordConfig(server.url("/token"), "bob");
            OAuth2Manager manager = new OAuth2Manager(OAuth2HttpTestSupport.mockMontoyaApi(server));

            TokenStore.TokenEntry resolved = manager.getValidToken(bob);

            assertThat(resolved.accessToken).isEqualTo("bob-access");
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(TokenStore.get(TokenStore.makeKey(alice)).accessToken).isEqualTo("alice-access");
            assertThat(TokenStore.get(TokenStore.makeKey(bob)).accessToken).isEqualTo("bob-access");
        }
    }

    private static OAuth2Config baseConfig(String tokenUrl) {
        OAuth2Config config = new OAuth2Config();
        config.grantType = OAuth2Config.GrantType.CLIENT_CREDENTIALS;
        config.tokenUrl = tokenUrl;
        config.clientId = "demo-client";
        config.clientSecret = "demo-secret";
        config.clientAuth = "body";
        return config;
    }

    private static OAuth2Config passwordConfig(String tokenUrl, String username) {
        OAuth2Config config = baseConfig(tokenUrl);
        config.grantType = OAuth2Config.GrantType.PASSWORD;
        config.username = username;
        config.password = "wonderland";
        config.scope = "scope:read";
        return config;
    }

    private static TokenStore.TokenEntry expiredEntry(String accessToken, String refreshToken) {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = accessToken;
        entry.refreshToken = refreshToken;
        entry.tokenType = "Bearer";
        entry.acquiredAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        entry.expiresAt = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(5);
        return entry;
    }

    private static TokenStore.TokenEntry resolve(OAuth2Manager manager, OAuth2Config config) {
        try {
            return manager.getValidToken(config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
