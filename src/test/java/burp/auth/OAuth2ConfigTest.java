package burp.auth;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ConfigTest {
    @Test
    void fromVariablesReadsRedirectUriWhenProvided() {
        Map<String, String> vars = new HashMap<>();
        vars.put("oauth2_token_url", "https://auth.example.test/token");
        vars.put("oauth2_auth_url", "https://auth.example.test/authorize");
        vars.put("oauth2_client_id", "client");
        vars.put("oauth2_client_secret", "secret");
        vars.put("oauth2_grant", "authorization_code");
        vars.put("oauth2_redirect_uri", "http://127.0.0.1:9988/oauth/callback");

        OAuth2Config config = OAuth2Config.fromVariables(vars);

        assertThat(config.redirectUri).isEqualTo("http://127.0.0.1:9988/oauth/callback");
    }

    @Test
    void fromVariablesNormalizesClientAuthAndPkceFlags() {
        Map<String, String> vars = new HashMap<>();
        vars.put("oauth2_token_url", "https://auth.example.test/token");
        vars.put("oauth2_client_id", "client");
        vars.put("oauth2_client_secret", "secret");
        vars.put("oauth2_client_auth", "prefer_basic");
        vars.put("oauth2_use_pkce", "false");

        OAuth2Config config = OAuth2Config.fromVariables(vars);

        assertThat(config.clientAuth).isEqualTo("prefer_basic");
        assertThat(config.usePkce).isFalse();
        assertThat(config.isValid()).isTrue();
    }

    @Test
    void validationHonorsClientAuthenticationModeAndGrantRequirements() {
        OAuth2Config bodyClient = baseConfig();
        bodyClient.clientAuth = "body";
        bodyClient.clientSecret = null;
        assertThat(bodyClient.isValid()).isFalse();

        OAuth2Config publicClient = baseConfig();
        publicClient.clientAuth = "none";
        publicClient.clientSecret = null;
        assertThat(publicClient.isValid()).isTrue();

        OAuth2Config passwordGrant = baseConfig();
        passwordGrant.grantType = OAuth2Config.GrantType.PASSWORD;
        passwordGrant.username = "alice";
        passwordGrant.password = "wonderland";
        assertThat(passwordGrant.isValid()).isTrue();

        OAuth2Config missingPassword = baseConfig();
        missingPassword.grantType = OAuth2Config.GrantType.PASSWORD;
        missingPassword.username = "alice";
        missingPassword.password = null;
        assertThat(missingPassword.isValid()).isFalse();

        OAuth2Config refreshGrant = baseConfig();
        refreshGrant.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
        refreshGrant.refreshToken = "refresh-123";
        assertThat(refreshGrant.isValid()).isTrue();
    }

    private static OAuth2Config baseConfig() {
        OAuth2Config config = new OAuth2Config();
        config.tokenUrl = "https://auth.example.test/token";
        config.clientId = "client";
        config.clientSecret = "secret";
        return config;
    }
}
