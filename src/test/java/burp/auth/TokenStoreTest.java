package burp.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TokenStoreTest {

    @AfterEach
    void tearDown() {
        TokenStore.clearAll();
    }

    @Test
    void tokenEntryHonorsExpiryBoundaryAndBuffer() {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = "access";
        entry.expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);

        assertThat(entry.isExpired(0)).isFalse();
        assertThat(entry.isValid(0)).isTrue();

        entry.expiresAt = System.currentTimeMillis() + 500L;
        assertThat(entry.isExpired(1)).isTrue();
        assertThat(entry.isValid(1)).isFalse();
    }

    @Test
    void storeGetClearAndClearAllIsolateTokenEntries() {
        OAuth2Config first = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-a");
        OAuth2Config second = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-b");

        TokenStore.TokenEntry firstEntry = token("first");
        TokenStore.TokenEntry secondEntry = token("second");
        String firstKey = TokenStore.makeKey(first);
        String secondKey = TokenStore.makeKey(second);

        TokenStore.store(firstKey, firstEntry);
        TokenStore.store(secondKey, secondEntry);

        assertThat(TokenStore.get(firstKey).accessToken).isEqualTo("first");
        assertThat(TokenStore.get(secondKey).accessToken).isEqualTo("second");

        TokenStore.clear(firstKey);
        assertThat(TokenStore.get(firstKey)).isNull();
        assertThat(TokenStore.get(secondKey).accessToken).isEqualTo("second");

        TokenStore.clearAll();
        assertThat(TokenStore.get(secondKey)).isNull();
    }

    @Test
    void makeKeySeparatesGrantTypeAndClientIdentity() {
        OAuth2Config clientCredentials = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-a");
        OAuth2Config passwordGrant = config(OAuth2Config.GrantType.PASSWORD, "https://auth.example.test/token", "client-a");
        OAuth2Config otherClient = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-b");

        assertThat(TokenStore.makeKey(clientCredentials)).isNotEqualTo(TokenStore.makeKey(passwordGrant));
        assertThat(TokenStore.makeKey(clientCredentials)).isNotEqualTo(TokenStore.makeKey(otherClient));
    }

    @Test
    void makeKeySeparatesScopesAndUserContextsToAvoidCrossProfileLeakage() {
        OAuth2Config alice = config(OAuth2Config.GrantType.PASSWORD, "https://auth.example.test/token", "client-a");
        alice.username = "alice";
        alice.password = "wonderland";
        alice.scope = "scope:read";

        OAuth2Config bob = config(OAuth2Config.GrantType.PASSWORD, "https://auth.example.test/token", "client-a");
        bob.username = "bob";
        bob.password = "wonderland";
        bob.scope = "scope:read";

        OAuth2Config readScope = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-a");
        readScope.scope = "scope:read";

        OAuth2Config writeScope = config(OAuth2Config.GrantType.CLIENT_CREDENTIALS, "https://auth.example.test/token", "client-a");
        writeScope.scope = "scope:write";

        assertThat(TokenStore.makeKey(alice)).isNotEqualTo(TokenStore.makeKey(bob));
        assertThat(TokenStore.makeKey(readScope)).isNotEqualTo(TokenStore.makeKey(writeScope));
    }

    private static OAuth2Config config(OAuth2Config.GrantType grantType, String tokenUrl, String clientId) {
        OAuth2Config config = new OAuth2Config();
        config.grantType = grantType;
        config.tokenUrl = tokenUrl;
        config.clientId = clientId;
        return config;
    }

    private static TokenStore.TokenEntry token(String accessToken) {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = accessToken;
        entry.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        return entry;
    }
}
