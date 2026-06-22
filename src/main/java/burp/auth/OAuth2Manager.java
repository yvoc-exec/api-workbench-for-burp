package burp.auth;

import burp.api.montoya.MontoyaApi;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central OAuth2 token lifecycle manager.
 * Integrates with VariableResolver to inject tokens into requests.
 */
public class OAuth2Manager {
    private static final ConcurrentHashMap<String, LockRef> TOKEN_LOCKS = new ConcurrentHashMap<>();
    private final MontoyaApi api;

    public OAuth2Manager(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Acquire a new token for the given config.
     */
    public TokenStore.TokenEntry acquireToken(OAuth2Config config) throws Exception {
        if (config == null) {
            recordDiagnostic(DiagnosticSeverity.ERROR, "OAuth2 configuration is null", null);
            throw new Exception("OAuth2 configuration is null");
        }
        if (!config.isValid()) {
            recordDiagnostic(DiagnosticSeverity.ERROR, "Invalid OAuth2 configuration", null);
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
        recordDiagnostic(DiagnosticSeverity.INFO, "OAuth2 token acquired",
                "grant=" + config.grantType +
                        "\nkey=" + key +
                        "\nexpiresAt=" + entry.expiresAt);
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

        LockRef lockRef = acquireLockRef(key);
        lockRef.lock.lock();
        try {
            entry = TokenStore.get(key);
            if (entry != null && entry.isValid(config.tokenExpiryBuffer)) {
                return entry;
            }

            // Try refresh first if we have a refresh token
            if (entry != null && entry.refreshToken != null && !entry.refreshToken.isEmpty()) {
                try {
                    api.logging().logToOutput("OAuth2 token expired. Attempting refresh...");
                    recordDiagnostic(DiagnosticSeverity.INFO, "OAuth2 token refresh started", "key=" + key);
                    OAuth2Config refreshConfig = copyForRefresh(config, entry.refreshToken);
                    entry = new RefreshTokenHandler().execute(refreshConfig, api);
                    TokenStore.store(key, entry);
                    recordDiagnostic(DiagnosticSeverity.INFO, "OAuth2 token refresh completed",
                            "key=" + key + "\nexpiresAt=" + entry.expiresAt);
                    return entry;
                } catch (Exception e) {
                    api.logging().logToOutput("Refresh failed: " + e.getMessage() + ". Re-authenticating...");
                    recordDiagnostic(DiagnosticSeverity.WARNING, "OAuth2 token refresh failed",
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }

            // Full re-auth
            recordDiagnostic(DiagnosticSeverity.INFO, "OAuth2 token re-authentication started", "key=" + key);
            return acquireToken(config);
        } finally {
            lockRef.lock.unlock();
            releaseLockRef(key, lockRef);
        }
    }

    /**
     * Check if token needs refresh and do so. Call before each request in runner.
     */
    public void refreshIfNeeded(Map<String, String> variables) {
        if (variables == null) return;
        try {
            OAuth2Config config = OAuth2Config.fromVariables(variables);
            if (config.isValid()) {
                recordDiagnostic(DiagnosticSeverity.DEBUG, "OAuth2 refresh check passed", "key=" + TokenStore.makeKey(config));
                getValidToken(config);
            } else {
                recordDiagnostic(DiagnosticSeverity.DEBUG, "OAuth2 refresh check skipped", "Invalid configuration");
            }
        } catch (Exception e) {
            // Silently fail - request will fail with 401 if token is truly bad
            api.logging().logToError("OAuth2 refresh check failed: " + e.getMessage());
            recordDiagnostic(DiagnosticSeverity.ERROR, "OAuth2 refresh check failed",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public void clearTokens() {
        TokenStore.clearAll();
        api.logging().logToOutput("All OAuth2 tokens cleared");
        recordDiagnostic(DiagnosticSeverity.INFO, "All OAuth2 tokens cleared", null);
    }

    public static String getAccessTokenVariableName() {
        return "oauth2_access_token";
    }

    private void recordDiagnostic(DiagnosticSeverity severity, String message, String details) {
        DiagnosticEvent event = DiagnosticEvent.of(DiagnosticOperation.OAUTH2_TOKEN_FETCH, severity, "OAuth2Manager", message);
        event.details = details;
        DiagnosticStore.getInstance().record(event);
    }

    private static OAuth2Config copyForRefresh(OAuth2Config source, String refreshToken) {
        OAuth2Config copy = new OAuth2Config();
        copy.grantType = OAuth2Config.GrantType.REFRESH_TOKEN;
        copy.tokenUrl = source.tokenUrl;
        copy.authUrl = source.authUrl;
        copy.redirectUri = source.redirectUri;
        copy.clientId = source.clientId;
        copy.clientSecret = source.clientSecret;
        copy.username = source.username;
        copy.password = source.password;
        copy.scope = source.scope;
        copy.refreshToken = refreshToken;
        copy.usePkce = source.usePkce;
        copy.tokenExpiryBuffer = source.tokenExpiryBuffer;
        copy.clientAuth = source.clientAuth;
        return copy;
    }

    private static LockRef acquireLockRef(String key) {
        return TOKEN_LOCKS.compute(key, (ignored, current) -> {
            LockRef ref = current != null ? current : new LockRef();
            ref.users.incrementAndGet();
            return ref;
        });
    }

    private static void releaseLockRef(String key, LockRef lockRef) {
        TOKEN_LOCKS.computeIfPresent(key, (ignored, current) -> {
            if (current != lockRef) {
                return current;
            }
            int remaining = current.users.decrementAndGet();
            if (remaining <= 0 && !current.lock.isLocked() && !current.lock.hasQueuedThreads()) {
                return null;
            }
            return current;
        });
    }

    private static final class LockRef {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger users = new AtomicInteger();
    }
}
