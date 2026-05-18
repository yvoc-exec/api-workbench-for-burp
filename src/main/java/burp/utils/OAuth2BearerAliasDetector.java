package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.BearerTokenAliasCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds bearer-token aliases used within a single collection and binds selected
 * aliases back into that collection's runtime variables.
 */
public final class OAuth2BearerAliasDetector {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");
    private static final Pattern BEARER_HEADER_PATTERN = Pattern.compile("(?i)^Bearer\\s+(.+)$");
    private static final Set<String> EXCLUDED_ALIASES = Set.of(
            "oauth2_access_token",
            "oauth2_refresh_token",
            "oauth2_client_id",
            "oauth2_client_secret"
    );
    private static final Set<String> BEARER_AUTH_FIELD_KEYS = Set.of(
            normalizeKey("token"),
            normalizeKey("value"),
            normalizeKey("accessToken"),
            normalizeKey("authToken"),
            normalizeKey("bearerToken"),
            normalizeKey("jwt"),
            normalizeKey("apiToken")
    );

    private OAuth2BearerAliasDetector() {
    }

    public static List<BearerTokenAliasCandidate> detect(ApiCollection collection, String acquiredAccessToken) {
        if (collection == null || collection.requests == null || collection.requests.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, RequestStats> statsByAlias = new LinkedHashMap<>();

        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }

            Set<String> aliasesInRequest = new LinkedHashSet<>();
            scanAuthorizationHeaders(request, aliasesInRequest);
            scanBearerAuthProperties(request, aliasesInRequest);

            for (String alias : aliasesInRequest) {
                if (!isEligibleAlias(alias)) {
                    continue;
                }
                statsByAlias
                        .computeIfAbsent(alias, ignored -> new RequestStats())
                        .requests
                        .add(request);
            }
        }

        List<BearerTokenAliasCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, RequestStats> entry : statsByAlias.entrySet()) {
            String alias = entry.getKey();
            String currentValue = collection.runtimeVars != null ? collection.runtimeVars.get(alias) : null;
            boolean defaultSelected = currentValue == null || currentValue.isBlank();
            String overwriteStatus = buildOverwriteStatus(currentValue, acquiredAccessToken);
            candidates.add(new BearerTokenAliasCandidate(
                    alias,
                    entry.getValue().requests.size(),
                    currentValue,
                    defaultSelected,
                    overwriteStatus
            ));
        }

        return candidates;
    }

    public static void bindSelectedAliases(ApiCollection collection,
                                           List<BearerTokenAliasCandidate> candidates,
                                           Collection<String> selectedAliases,
                                           String accessToken) {
        if (collection == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        if (selectedAliases == null || selectedAliases.isEmpty()) {
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }

        Map<String, String> updates = new LinkedHashMap<>();
        for (BearerTokenAliasCandidate candidate : candidates) {
            if (candidate == null || candidate.alias == null) {
                continue;
            }
            if (!selectedAliases.contains(candidate.alias)) {
                continue;
            }
            updates.put(candidate.alias, accessToken);
        }

        if (!updates.isEmpty()) {
            collection.putAllRuntimeVars(updates);
        }
    }

    private static void scanAuthorizationHeaders(ApiRequest request, Set<String> aliasesInRequest) {
        if (request.headers == null || request.headers.isEmpty()) {
            return;
        }

        for (ApiRequest.Header header : request.headers) {
            if (header == null || header.disabled) {
                continue;
            }
            if (header.key == null || header.value == null) {
                continue;
            }
            if (!"authorization".equalsIgnoreCase(header.key.trim())) {
                continue;
            }

            Matcher bearerMatcher = BEARER_HEADER_PATTERN.matcher(header.value.trim());
            if (!bearerMatcher.matches()) {
                continue;
            }
            scanTemplateVariables(bearerMatcher.group(1), aliasesInRequest);
        }
    }

    private static void scanBearerAuthProperties(ApiRequest request, Set<String> aliasesInRequest) {
        if (request.auth == null || request.auth.type == null || request.auth.properties == null ||
                request.auth.properties.isEmpty()) {
            return;
        }

        String authType = request.auth.type.trim().toLowerCase();
        if (!"bearer".equals(authType) && !"oauth2".equals(authType)) {
            return;
        }

        for (Map.Entry<String, String> entry : request.auth.properties.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!isBearerTokenField(entry.getKey())) {
                continue;
            }
            scanTemplateVariables(entry.getValue(), aliasesInRequest);
        }
    }

    private static void scanTemplateVariables(String input, Set<String> aliasesInRequest) {
        if (input == null || input.isEmpty()) {
            return;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        while (matcher.find()) {
            String alias = matcher.group(1) != null ? matcher.group(1).trim() : "";
            String defaultValue = matcher.group(2);
            if (alias.isEmpty() || defaultValue != null) {
                continue;
            }
            aliasesInRequest.add(alias);
        }
    }

    private static boolean isEligibleAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return false;
        }
        String normalized = alias.trim().toLowerCase();
        if (normalized.startsWith("oauth2_")) {
            return false;
        }
        return !EXCLUDED_ALIASES.contains(normalized);
    }

    private static boolean isBearerTokenField(String key) {
        return BEARER_AUTH_FIELD_KEYS.contains(normalizeKey(key));
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static String buildOverwriteStatus(String currentValue, String acquiredAccessToken) {
        if (currentValue == null || currentValue.isBlank()) {
            return "missing; will bind";
        }
        if (acquiredAccessToken != null && !acquiredAccessToken.isBlank() && acquiredAccessToken.equals(currentValue)) {
            return "already set to acquired token";
        }
        return "already set; will overwrite if selected";
    }

    private static final class RequestStats {
        private final Set<ApiRequest> requests = Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
