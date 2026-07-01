package burp.models;

import burp.utils.RedirectOrigin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RedirectPolicy {
    private static final List<String> BUILT_IN_SENSITIVE_HEADER_NAMES = List.of(
            "Authorization",
            "Cookie",
            "Proxy-Authorization",
            "X-API-Key",
            "API-Key",
            "X-Auth-Token"
    );

    public int maxHops = 10;
    public RedirectCrossOriginMode crossOriginMode = RedirectCrossOriginMode.STRIP_SENSITIVE;
    public List<String> additionalSensitiveHeaderNames = new ArrayList<>();
    public List<TrustedRedirectRule> trustedRules = new ArrayList<>();

    public static RedirectPolicy defaults() {
        return new RedirectPolicy();
    }

    public static RedirectPolicy copyOf(RedirectPolicy source) {
        RedirectPolicy copy = new RedirectPolicy();
        if (source == null) {
            return copy;
        }
        copy.maxHops = source.maxHops;
        copy.crossOriginMode = source.crossOriginMode;
        copy.additionalSensitiveHeaderNames = source.additionalSensitiveHeaderNames != null
                ? new ArrayList<>(source.additionalSensitiveHeaderNames)
                : new ArrayList<>();
        copy.trustedRules = new ArrayList<>();
        if (source.trustedRules != null) {
            for (TrustedRedirectRule rule : source.trustedRules) {
                TrustedRedirectRule ruleCopy = TrustedRedirectRule.copyOf(rule);
                if (ruleCopy != null) {
                    copy.trustedRules.add(ruleCopy);
                }
            }
        }
        return copy;
    }

    public void normalize() {
        if (maxHops < 1) {
            maxHops = 1;
        } else if (maxHops > 20) {
            maxHops = 20;
        }
        if (crossOriginMode == null) {
            crossOriginMode = RedirectCrossOriginMode.STRIP_SENSITIVE;
        }
        additionalSensitiveHeaderNames = normalizeHeaderNames(additionalSensitiveHeaderNames);
        trustedRules = normalizeTrustedRules(trustedRules);
    }

    public Set<String> effectiveSensitiveHeaderNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : BUILT_IN_SENSITIVE_HEADER_NAMES) {
            addIfAbsent(names, name);
        }
        for (String name : additionalSensitiveHeaderNames != null ? additionalSensitiveHeaderNames : List.<String>of()) {
            addIfAbsent(names, name);
        }
        return Collections.unmodifiableSet(names);
    }

    public boolean isTrustedHeaderAllowed(String sourceOrigin, String targetOrigin, String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        if ("proxy-authorization".equalsIgnoreCase(headerName.trim())) {
            return false;
        }
        String source = RedirectOrigin.canonicalOrigin(sourceOrigin);
        String target = RedirectOrigin.canonicalOrigin(targetOrigin);
        if (source == null || target == null || !isHttpsOrigin(target)) {
            return false;
        }
        for (TrustedRedirectRule rule : trustedRules != null ? trustedRules : List.<TrustedRedirectRule>of()) {
            if (rule == null) {
                continue;
            }
            if (source.equals(rule.sourceOrigin) && target.equals(rule.targetOrigin) && rule.hasAllowedHeader(headerName)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTrustedRule(String sourceOrigin, String targetOrigin) {
        String source = RedirectOrigin.canonicalOrigin(sourceOrigin);
        String target = RedirectOrigin.canonicalOrigin(targetOrigin);
        if (source == null || target == null) {
            return false;
        }
        for (TrustedRedirectRule rule : trustedRules != null ? trustedRules : List.<TrustedRedirectRule>of()) {
            if (rule == null) {
                continue;
            }
            if (source.equals(rule.sourceOrigin) && target.equals(rule.targetOrigin) && isHttpsOrigin(target)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizeHeaderNames(List<String> source) {
        List<String> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : source) {
            String trimmed = name != null ? name.trim() : "";
            if (trimmed.isBlank()) {
                continue;
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static List<TrustedRedirectRule> normalizeTrustedRules(List<TrustedRedirectRule> source) {
        List<TrustedRedirectRule> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }
        Map<String, TrustedRedirectRule> byKey = new LinkedHashMap<>();
        for (TrustedRedirectRule rule : source) {
            if (rule == null) {
                continue;
            }
            String sourceOrigin = RedirectOrigin.canonicalOrigin(rule.sourceOrigin);
            String targetOrigin = RedirectOrigin.canonicalOrigin(rule.targetOrigin);
            if (sourceOrigin == null || targetOrigin == null || !isHttpsOrigin(targetOrigin)) {
                continue;
            }
            String key = sourceOrigin + "\u0000" + targetOrigin;
            TrustedRedirectRule normalizedRule = byKey.computeIfAbsent(key, k -> {
                TrustedRedirectRule copy = new TrustedRedirectRule();
                copy.sourceOrigin = sourceOrigin;
                copy.targetOrigin = targetOrigin;
                copy.allowedHeaderNames = new ArrayList<>();
                return copy;
            });
            if (rule.allowedHeaderNames == null) {
                continue;
            }
            for (String headerName : TrustedRedirectRule.normalizeHeaderNames(rule.allowedHeaderNames)) {
                if (headerName == null || headerName.isBlank() || "proxy-authorization".equalsIgnoreCase(headerName)) {
                    continue;
                }
                boolean seen = false;
                for (String existing : normalizedRule.allowedHeaderNames) {
                    if (existing != null && existing.equalsIgnoreCase(headerName)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    normalizedRule.allowedHeaderNames.add(headerName);
                }
            }
        }
        normalized.addAll(byKey.values());
        return normalized;
    }

    private static void addIfAbsent(LinkedHashSet<String> names, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return;
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (String existing : names) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        names.add(trimmed);
    }

    private static boolean isHttpsOrigin(String origin) {
        return origin != null && origin.startsWith("https://");
    }
}
