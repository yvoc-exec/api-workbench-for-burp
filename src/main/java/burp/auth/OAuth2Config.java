package burp.auth;

import java.util.*;

/**
 * OAuth2 configuration for a collection.
 */
public class OAuth2Config {
    public enum GrantType {
        CLIENT_CREDENTIALS, PASSWORD, AUTHORIZATION_CODE, REFRESH_TOKEN
    }

    public GrantType grantType = GrantType.CLIENT_CREDENTIALS;
    public String tokenUrl;
    public String authUrl;           // For authorization code flow
    public String redirectUri = "http://localhost:9876/callback";
    public String clientId;
    public String clientSecret;
    public String username;          // ROPC
    public String password;          // ROPC
    public String scope;
    public String refreshToken;
    public boolean usePkce = true;
    public int tokenExpiryBuffer = 60; // seconds before expiry to refresh
    public String clientAuth = "body"; // "body" or "basic"

    public static OAuth2Config fromVariables(Map<String, String> vars) {
        OAuth2Config config = new OAuth2Config();
        String grant = vars.getOrDefault("oauth2_grant", "client_credentials");
        try {
            config.grantType = GrantType.valueOf(grant.toUpperCase().replace("-", "_"));
        } catch (Exception e) {
            config.grantType = GrantType.CLIENT_CREDENTIALS;
        }
        config.tokenUrl = vars.get("oauth2_token_url");
        config.authUrl = vars.get("oauth2_auth_url");
        config.clientId = vars.get("oauth2_client_id");
        config.clientSecret = vars.get("oauth2_client_secret");
        if (vars.containsKey("oauth2_client_auth")) {
            config.clientAuth = vars.get("oauth2_client_auth");
        }
        config.username = vars.get("oauth2_username");
        config.password = vars.get("oauth2_password");
        config.scope = vars.get("oauth2_scope");
        config.refreshToken = vars.get("oauth2_refresh_token");
        if (vars.containsKey("oauth2_use_pkce")) {
            config.usePkce = Boolean.parseBoolean(vars.get("oauth2_use_pkce"));
        }
        return config;
    }

    public boolean isValid() {
        if (tokenUrl == null || tokenUrl.isEmpty()) return false;
        if (clientId == null || clientId.isEmpty()) return false;
        switch (grantType) {
            case CLIENT_CREDENTIALS:
                return clientSecret != null && !clientSecret.isEmpty();
            case PASSWORD:
                return username != null && password != null;
            case AUTHORIZATION_CODE:
                return authUrl != null && !authUrl.isEmpty();
            case REFRESH_TOKEN:
                return refreshToken != null && !refreshToken.isEmpty();
        }
        return false;
    }

    public String getVariablePrefix() {
        return "oauth2_";
    }
}
