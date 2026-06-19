package burp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token storage with expiry tracking.
 * Tokens are never persisted to disk.
 */
public class TokenStore {
    private static final Map<String, TokenEntry> store = new ConcurrentHashMap<>();

    public static class TokenEntry {
        public String accessToken;
        public String refreshToken;
        public String tokenType;
        public long expiresAt;      // epoch millis
        public String scope;
        public long acquiredAt;

        public boolean isExpired(int bufferSeconds) {
            return System.currentTimeMillis() + (bufferSeconds * 1000L) >= expiresAt;
        }

        public boolean isValid(int bufferSeconds) {
            return accessToken != null && !accessToken.isEmpty() && !isExpired(bufferSeconds);
        }
    }

    public static void store(String key, TokenEntry entry) {
        store.put(key, entry);
    }

    public static TokenEntry get(String key) {
        return store.get(key);
    }

    public static void clear(String key) {
        store.remove(key);
    }

    public static void clearAll() {
        store.clear();
    }

    public static String makeKey(OAuth2Config config) {
        if (config == null) {
            return "null";
        }
        String canonical = String.join("\u001f",
                safe(config.grantType != null ? config.grantType.name() : null),
                safe(config.tokenUrl),
                safe(config.authUrl),
                safe(config.redirectUri),
                safe(config.clientId),
                safe(config.clientSecret),
                safe(config.username),
                safe(config.password),
                safe(config.scope),
                safe(config.refreshToken),
                safe(normalizeClientAuth(config.clientAuth)),
                String.valueOf(config.usePkce));
        return safe(config.grantType != null ? config.grantType.name() : null)
                + "|" + safe(config.tokenUrl)
                + "|" + safe(config.clientId)
                + "|" + fingerprint(canonical);
    }

    private static String normalizeClientAuth(String clientAuth) {
        String mode = clientAuth == null ? "body" : clientAuth.trim().toLowerCase(Locale.ROOT);
        if ("prefer_basic".equals(mode)) {
            return "basic";
        }
        if (!"basic".equals(mode) && !"none".equals(mode)) {
            return "body";
        }
        return mode;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(safe(value).hashCode());
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
