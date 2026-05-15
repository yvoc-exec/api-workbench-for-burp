package burp.auth;

import burp.api.montoya.MontoyaApi;

public class PasswordGrantHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) throws Exception {
        ClientCredentialsHandler.validateBodySecretIfNeeded(config);
        StringBuilder body = new StringBuilder();
        ClientCredentialsHandler.appendFormParam(body, "grant_type", "password", true);
        ClientCredentialsHandler.appendFormParam(body, "client_id", config.clientId, false);
        if (ClientCredentialsHandler.isBodyClientSecret(config)) {
            ClientCredentialsHandler.appendFormParam(body, "client_secret", config.clientSecret, false);
        }
        ClientCredentialsHandler.appendFormParam(body, "username", config.username, false);
        ClientCredentialsHandler.appendFormParam(body, "password", config.password, false);
        if (config.scope != null && !config.scope.isEmpty()) {
            ClientCredentialsHandler.appendFormParam(body, "scope", config.scope, false);
        }
        return ClientCredentialsHandler.executeTokenRequest(config, body.toString(), api);
    }
}
