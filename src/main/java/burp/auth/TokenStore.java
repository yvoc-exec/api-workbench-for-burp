package burp.auth;

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
        return config.grantType + "|" + config.tokenUrl + "|" + config.clientId;
    }
}
