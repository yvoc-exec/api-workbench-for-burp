package burp.auth;

import burp.api.montoya.MontoyaApi;
import java.util.Map;

/**
 * Central OAuth2 token lifecycle manager.
 * Integrates with VariableResolver to inject tokens into requests.
 */
public class OAuth2Manager {
    private final MontoyaApi api;

    public OAuth2Manager(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Acquire a new token for the given config.
     */
    public TokenStore.TokenEntry acquireToken(OAuth2Config config) throws Exception {
        if (config == null) {
            throw new Exception("OAuth2 configuration is null");
        }
        if (!config.isValid()) {
            throw new Exception("Invalid OAuth2 configuration");
        }

        TokenStore.TokenEntry entry;
        switch (config.grantType) {
            case CLIENT_CREDENTIALS:
                entry = new ClientCredentialsHandler().execute(config, api);
                break;
            case PASSWORD:
                entry = new PasswordGrantHandler().execute(config, api);
                break;
            case AUTHORIZATION_CODE:
                entry = new AuthorizationCodeHandler(api).execute(config);
                break;
            case REFRESH_TOKEN:
                entry = new RefreshTokenHandler().execute(config, api);
                break;
            default:
                throw new Exception("Unsupported grant type: " + config.grantType);
        }

        String key = TokenStore.makeKey(config);
        TokenStore.store(key, entry);
        api.logging().logToOutput("OAuth2 token acquired. Expires in ~" +
                ((entry.expiresAt - System.currentTimeMillis()) / 1000) + "s");
        return entry;
    }

    /**
     * Get a valid token, refreshing if necessary.
     */
    public TokenStore.TokenEntry getValidToken(OAuth2Config config) throws Exception {
        if (config == null) {
            throw new Exception("OAuth2 configuration is null");
        }
        String key = TokenStore.makeKey(config);
        TokenStore.TokenEntry entry = TokenStore.get(key);

        if (entry != null && entry.isValid(config.tokenExpiryBuffer)) {
            return entry;
        }

        // Try refresh first if we have a refresh token
        if (entry != null && entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
            try {
                api.logging().logToOutput("OAuth2 token expired. Attempting refresh...");
                config.refreshToken = entry.refreshToken;
                config.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
                entry = new RefreshTokenHandler().execute(config, api);
                TokenStore.store(key, entry);
                return entry;
            } catch (Exception e) {
                api.logging().logToOutput("Refresh failed: " + e.getMessage() + ". Re-authenticating...");
            }
        }

        // Full re-auth
        return acquireToken(config);
    }

    /**
     * Check if token needs refresh and do so. Call before each request in runner.
     */
    public void refreshIfNeeded(Map<String, String> variables) {
        if (variables == null) return;
        try {
            OAuth2Config config = OAuth2Config.fromVariables(variables);
            if (config.isValid()) {
                getValidToken(config);
            }
        } catch (Exception e) {
            // Silently fail — request will fail with 401 if token is truly bad
            api.logging().logToError("OAuth2 refresh check failed: " + e.getMessage());
        }
    }

    public void clearTokens() {
        TokenStore.clearAll();
        api.logging().logToOutput("All OAuth2 tokens cleared");
    }

    public static String getAccessTokenVariableName() {
        return "oauth2_access_token";
    }
}
