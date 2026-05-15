package burp.auth;

import burp.api.montoya.MontoyaApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class PasswordGrantHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        String body = "grant_type=password" +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8);
        if (!ClientCredentialsHandler.isBasicAuth(config)) {
            body += "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8);
        }
        body += "&username=" + URLEncoder.encode(config.username, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(config.password, StandardCharsets.UTF_8);
        if (config.scope != null && !config.scope.isEmpty()) {
            body += "&scope=" + URLEncoder.encode(config.scope, StandardCharsets.UTF_8);
        }
        return ClientCredentialsHandler.executeTokenRequest(config, body, api);
    }
}
