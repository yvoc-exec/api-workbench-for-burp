package burp.auth;

import burp.api.montoya.MontoyaApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class RefreshTokenHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        String body = "grant_type=refresh_token" +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8);
        if (!ClientCredentialsHandler.isBasicAuth(config)) {
            body += "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8);
        }
        body += "&refresh_token=" + URLEncoder.encode(config.refreshToken, StandardCharsets.UTF_8);
        return ClientCredentialsHandler.executeTokenRequest(config, body, api);
    }
}
