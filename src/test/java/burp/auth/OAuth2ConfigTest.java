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
}
