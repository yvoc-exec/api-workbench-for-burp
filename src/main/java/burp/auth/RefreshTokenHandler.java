package burp.auth;

import burp.api.montoya.MontoyaApi;

public class RefreshTokenHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        ClientCredentialsHandler.validateBodySecretIfNeeded(config);
        StringBuilder body = new StringBuilder();
        ClientCredentialsHandler.appendFormParam(body, "grant_type", "refresh_token", true);
        ClientCredentialsHandler.appendFormParam(body, "client_id", config.clientId, false);
        if (ClientCredentialsHandler.isBodyClientSecret(config)) {
            ClientCredentialsHandler.appendFormParam(body, "client_secret", config.clientSecret, false);
        }
        ClientCredentialsHandler.appendFormParam(body, "refresh_token", config.refreshToken, false);
        return ClientCredentialsHandler.executeTokenRequest(config, body.toString(), api);
    }
}
