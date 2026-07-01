package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class TrustedRedirectRule {
    public String sourceOrigin;
    public String targetOrigin;
    public List<String> allowedHeaderNames = new ArrayList<>();

    public TrustedRedirectRule() {
    }

    public static TrustedRedirectRule copyOf(TrustedRedirectRule source) {
        if (source == null) {
            return null;
        }
        TrustedRedirectRule copy = new TrustedRedirectRule();
        copy.sourceOrigin = source.sourceOrigin;
        copy.targetOrigin = source.targetOrigin;
        copy.allowedHeaderNames = source.allowedHeaderNames != null
                ? new ArrayList<>(source.allowedHeaderNames)
                : new ArrayList<>();
        return copy;
    }

    void normalizeAllowedHeaders() {
        allowedHeaderNames = normalizeHeaderNames(allowedHeaderNames);
        allowedHeaderNames.removeIf(name -> name != null && "proxy-authorization".equalsIgnoreCase(name.trim()));
    }

    boolean hasAllowedHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String candidate = headerName.trim();
        for (String allowed : allowedHeaderNames) {
            if (allowed != null && allowed.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    static List<String> normalizeHeaderNames(List<String> source) {
        List<String> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : source) {
            String trimmed = value != null ? value.trim() : "";
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
}
