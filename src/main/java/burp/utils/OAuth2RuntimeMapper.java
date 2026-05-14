package burp.utils;

import burp.models.ApiRequest;
import java.util.*;

/**
 * Normalizes OAuth2 auth metadata from parsed collections into canonical
 * runtime variable names used by RequestBuilder and OAuth2Manager.
 *
 * This bridges the gap between parser-specific property names (camelCase,
 * snake_case, etc.) and the uniform {@code oauth2_*} variable namespace.
 */
public class OAuth2RuntimeMapper {

    private static final Map<String, String> KEY_SYNONYMS = new HashMap<>();
    static {
        KEY_SYNONYMS.put("granttype", "oauth2_grant");
        KEY_SYNONYMS.put("grant_type", "oauth2_grant");
        KEY_SYNONYMS.put("grant", "oauth2_grant");

        KEY_SYNONYMS.put("accesstokenurl", "oauth2_token_url");
        KEY_SYNONYMS.put("access_token_url", "oauth2_token_url");
        KEY_SYNONYMS.put("tokenurl", "oauth2_token_url");
        KEY_SYNONYMS.put("token_url", "oauth2_token_url");

        KEY_SYNONYMS.put("authorizationurl", "oauth2_auth_url");
        KEY_SYNONYMS.put("authorization_url", "oauth2_auth_url");
        KEY_SYNONYMS.put("authurl", "oauth2_auth_url");
        KEY_SYNONYMS.put("auth_url", "oauth2_auth_url");

        KEY_SYNONYMS.put("clientid", "oauth2_client_id");
        KEY_SYNONYMS.put("client_id", "oauth2_client_id");

        KEY_SYNONYMS.put("clientsecret", "oauth2_client_secret");
        KEY_SYNONYMS.put("client_secret", "oauth2_client_secret");

        KEY_SYNONYMS.put("scope", "oauth2_scope");
        KEY_SYNONYMS.put("username", "oauth2_username");
        KEY_SYNONYMS.put("password", "oauth2_password");

        KEY_SYNONYMS.put("refreshtoken", "oauth2_refresh_token");
        KEY_SYNONYMS.put("refresh_token", "oauth2_refresh_token");

        KEY_SYNONYMS.put("code", "oauth2_code");

        KEY_SYNONYMS.put("redirecturi", "oauth2_redirect_uri");
        KEY_SYNONYMS.put("redirect_uri", "oauth2_redirect_uri");

        KEY_SYNONYMS.put("codeverifier", "oauth2_code_verifier");
        KEY_SYNONYMS.put("code_verifier", "oauth2_code_verifier");

        KEY_SYNONYMS.put("clientauth", "oauth2_client_auth");
        KEY_SYNONYMS.put("clientauthentication", "oauth2_client_auth");
        KEY_SYNONYMS.put("client_auth", "oauth2_client_auth");

        KEY_SYNONYMS.put("accesstoken", "oauth2_access_token");
        KEY_SYNONYMS.put("access_token", "oauth2_access_token");
    }

    /**
     * Maps auth properties to canonical OAuth2 runtime variables.
     *
     * @param auth          the parsed auth object (may be null)
     * @param existingVars  current variable map to check for existing values
     * @return a map of canonical variable names to values
     */
    public static Map<String, String> mapAuthToVars(ApiRequest.Auth auth, Map<String, String> existingVars) {
        Map<String, String> mapped = new HashMap<>();
        if (auth == null || auth.properties == null || auth.properties.isEmpty()) {
            return mapped;
        }

        for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.isEmpty()) {
                continue;
            }

            String canonical = KEY_SYNONYMS.get(key.toLowerCase());
            if (canonical == null) {
                continue;
            }

            // Do not overwrite already-populated canonical values unless empty
            String existing = existingVars != null ? existingVars.get(canonical) : null;
            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            mapped.put(canonical, normalizeValue(canonical, value));
        }

        return mapped;
    }

    private static String normalizeValue(String canonicalKey, String value) {
        if ("oauth2_grant".equals(canonicalKey) && value != null) {
            // Normalize grant type to snake_case
            String normalized = value.replace('-', '_')
                                      .replace("clientCredentials", "client_credentials")
                                      .replace("authorizationCode", "authorization_code")
                                      .replace("refreshToken", "refresh_token")
                                      .toLowerCase();
            return normalized;
        }
        return value;
    }
}
