package burp.utils;

import burp.models.ApiRequest;

import java.util.*;

/**
 * Extracts OAuth2 configuration fields from an ApiRequest for populating the OAuth2 tab.
 * Follows a defined extraction priority: auth properties > body urlencoded > body formdata
 * > Authorization Basic header > existing variables (fallback only).
 */
public class OAuth2PopulateHelper {
    private static final java.util.regex.Pattern[] RAW_TOKEN_FIELD_PATTERNS = new java.util.regex.Pattern[] {
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])grant_type\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"grant_type\""),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])client_id\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"client_id\""),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])client_secret\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"client_secret\""),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])refresh_token\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"refresh_token\""),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])code_verifier\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"code_verifier\""),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])code\\s*="),
            java.util.regex.Pattern.compile("(?i)(^|[&\\s\\{\\[\\(\",])\"code\"")
    };

    private static final Map<String, String> KEY_SYNONYMS = new HashMap<>();
    static {
        KEY_SYNONYMS.put("granttype", "oauth2_grant");
        KEY_SYNONYMS.put("grant_type", "oauth2_grant");
        KEY_SYNONYMS.put("grant", "oauth2_grant");

        KEY_SYNONYMS.put("accesstokenurl", "oauth2_token_url");
        KEY_SYNONYMS.put("access_token_url", "oauth2_token_url");
        KEY_SYNONYMS.put("tokenurl", "oauth2_token_url");
        KEY_SYNONYMS.put("token_url", "oauth2_token_url");
        KEY_SYNONYMS.put("tokenendpoint", "oauth2_token_url");
        KEY_SYNONYMS.put("token_endpoint", "oauth2_token_url");

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
        KEY_SYNONYMS.put("clientauthentication", "oauth2_client_auth");
    }

    /**
     * Extracts OAuth2 fields from a request using the priority order defined in requirements.
     * Returns a map of canonical oauth2_* keys to values.
     */
    public static Map<String, String> extractOAuth2Fields(ApiRequest req) {
        Map<String, String> result = new LinkedHashMap<>();
        if (req == null) return result;

        // 1. request.auth properties
        if (req.auth != null && req.auth.properties != null) {
            for (Map.Entry<String, String> entry : req.auth.properties.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                String canonical = KEY_SYNONYMS.get(entry.getKey().toLowerCase());
                if (canonical != null) {
                    result.put(canonical, entry.getValue());
                }
            }
        }

        // 2. request body urlencoded fields
        if (req.body != null && req.body.urlencoded != null) {
            for (ApiRequest.Body.FormField field : req.body.urlencoded) {
                if (field.disabled || field.key == null) continue;
                String canonical = KEY_SYNONYMS.get(field.key.toLowerCase());
                if (canonical != null && field.value != null && !field.value.isEmpty()) {
                    if (!result.containsKey(canonical) || isEmpty(result.get(canonical))) {
                        result.put(canonical, field.value);
                    }
                }
            }
        }

        // 3. request body formdata fields
        if (req.body != null && req.body.formdata != null) {
            for (ApiRequest.Body.FormField field : req.body.formdata) {
                if (field.disabled || field.key == null) continue;
                String canonical = KEY_SYNONYMS.get(field.key.toLowerCase());
                if (canonical != null && field.value != null && !field.value.isEmpty()) {
                    if (!result.containsKey(canonical) || isEmpty(result.get(canonical))) {
                        result.put(canonical, field.value);
                    }
                }
            }
        }

        // 4. Authorization Basic header fallback (client_id:client_secret decode)
        if (req.headers != null) {
            for (ApiRequest.Header header : req.headers) {
                if (header.disabled || header.key == null || header.value == null) continue;
                if ("authorization".equalsIgnoreCase(header.key.trim())) {
                    String val = header.value.trim();
                    if (val.toLowerCase().startsWith("basic ")) {
                        String base64 = val.substring(6).trim();
                        try {
                            String decoded = new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
                            int colon = decoded.indexOf(':');
                            if (colon > 0) {
                                String user = decoded.substring(0, colon);
                                String pass = decoded.substring(colon + 1);
                                if (!result.containsKey("oauth2_client_id") || isEmpty(result.get("oauth2_client_id"))) {
                                    result.put("oauth2_client_id", user);
                                }
                                if ((!result.containsKey("oauth2_client_secret") || isEmpty(result.get("oauth2_client_secret")))
                                        && pass != null && !pass.isEmpty()) {
                                    result.put("oauth2_client_secret", pass);
                                }
                            }
                        } catch (Exception e) {
                            // ignore malformed basic auth
                        }
                    }
                }
            }
        }

        // Normalize grant type to snake_case
        if (result.containsKey("oauth2_grant")) {
            String grant = result.get("oauth2_grant").toLowerCase()
                    .replace("clientcredentials", "client_credentials")
                    .replace("authorizationcode", "authorization_code")
                    .replace("refreshtoken", "refresh_token")
                    .replace("-", "_");
            result.put("oauth2_grant", grant);
        }

        inferTokenUrlFromRequestUrl(req, result);

        return result;
    }

    /**
     * Merges extracted fields with existing variables. Extracted values take precedence;
     * existing vars fill gaps only (do not overwrite already-extracted values).
     */
    public static Map<String, String> mergeWithExisting(Map<String, String> extracted, Map<String, String> existingVars) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (extracted != null) merged.putAll(extracted);
        if (existingVars != null) {
            for (Map.Entry<String, String> entry : existingVars.entrySet()) {
                if (!merged.containsKey(entry.getKey()) || isEmpty(merged.get(entry.getKey()))) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static void inferTokenUrlFromRequestUrl(ApiRequest req, Map<String, String> result) {
        if (req == null || result == null || hasExistingTokenUrl(result)) {
            return;
        }
        if (req.url == null || req.url.isBlank()) {
            return;
        }
        if (!looksLikeTokenRequest(req)) {
            return;
        }

        result.put("oauth2_token_url", req.url.trim());
    }

    private static boolean hasExistingTokenUrl(Map<String, String> result) {
        return result != null && !isEmpty(result.get("oauth2_token_url"));
    }

    private static boolean looksLikeTokenRequest(ApiRequest req) {
        return hasTokenPath(req != null ? req.url : null) ||
                ((hasUrlEncodedBodyMode(req) || hasUrlEncodedContentType(req)) && hasOAuthTokenBodyFields(req));
    }

    private static boolean hasUrlEncodedBodyMode(ApiRequest req) {
        return req != null && req.body != null && "urlencoded".equalsIgnoreCase(req.body.mode);
    }

    private static boolean hasTokenPath(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        int queryIdx = normalized.indexOf('?');
        if (queryIdx >= 0) {
            normalized = normalized.substring(0, queryIdx);
        }
        int fragmentIdx = normalized.indexOf('#');
        if (fragmentIdx >= 0) {
            normalized = normalized.substring(0, fragmentIdx);
        }

        return normalized.contains("/oauth/token") ||
                normalized.contains("/oauth2/token") ||
                normalized.contains("/connect/token") ||
                normalized.contains("/auth/token") ||
                normalized.endsWith("/token") ||
                normalized.contains("/token/");
    }

    private static boolean hasUrlEncodedContentType(ApiRequest req) {
        if (req == null) {
            return false;
        }

        if (req.body != null && req.body.contentType != null &&
                req.body.contentType.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded")) {
            return true;
        }

        if (req.headers == null) {
            return false;
        }

        for (ApiRequest.Header header : req.headers) {
            if (header == null || header.disabled || header.key == null || header.value == null) {
                continue;
            }
            if ("content-type".equalsIgnoreCase(header.key.trim()) &&
                    header.value.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOAuthTokenBodyFields(ApiRequest req) {
        if (req == null || req.body == null) {
            return false;
        }

        if (req.body.urlencoded != null) {
            for (ApiRequest.Body.FormField field : req.body.urlencoded) {
                if (field == null || field.disabled) {
                    continue;
                }
                if (isOAuthTokenFieldName(field.key)) {
                    return true;
                }
            }
        }

        if (req.body.formdata != null) {
            for (ApiRequest.Body.FormField field : req.body.formdata) {
                if (field == null || field.disabled) {
                    continue;
                }
                if (isOAuthTokenFieldName(field.key)) {
                    return true;
                }
            }
        }

        if (req.body.raw != null && !req.body.raw.isBlank()) {
            return rawBodyContainsOAuthTokenField(req.body.raw);
        }

        return false;
    }

    private static boolean isOAuthTokenFieldName(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return "grant_type".equals(normalized) ||
                "client_id".equals(normalized) ||
                "client_secret".equals(normalized) ||
                "refresh_token".equals(normalized) ||
                "code".equals(normalized) ||
                "code_verifier".equals(normalized);
    }

    private static boolean rawBodyContainsOAuthTokenField(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }

        for (java.util.regex.Pattern pattern : RAW_TOKEN_FIELD_PATTERNS) {
            if (pattern.matcher(raw).find()) {
                return true;
            }
        }
        return false;
    }
}
