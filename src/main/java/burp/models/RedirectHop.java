package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class RedirectHop {
    public int hopNumber;
    public String sourceUrl;
    public String sourceMethod;
    public int statusCode;
    public String location;
    public String targetUrl;
    public String targetMethod;
    public long elapsedMs;
    public byte[] rawRequestBytes;
    public String rawRequestText;
    public String responseHeadersText;
    public byte[] responseBody;
    public boolean followed;
    public String failureReason;
    public List<String> forwardedSensitiveHeaderNames = new ArrayList<>();
    public List<String> strippedSensitiveHeaderNames = new ArrayList<>();

    public RedirectHop() {
    }

    public static RedirectHop copyOf(RedirectHop source) {
        if (source == null) {
            return null;
        }
        RedirectHop copy = new RedirectHop();
        copy.hopNumber = source.hopNumber;
        copy.sourceUrl = source.sourceUrl;
        copy.sourceMethod = source.sourceMethod;
        copy.statusCode = source.statusCode;
        copy.location = source.location;
        copy.targetUrl = source.targetUrl;
        copy.targetMethod = source.targetMethod;
        copy.elapsedMs = source.elapsedMs;
        copy.rawRequestBytes = source.rawRequestBytes != null ? source.rawRequestBytes.clone() : null;
        copy.rawRequestText = source.rawRequestText;
        copy.responseHeadersText = source.responseHeadersText;
        copy.responseBody = source.responseBody != null ? source.responseBody.clone() : null;
        copy.followed = source.followed;
        copy.failureReason = source.failureReason;
        copy.forwardedSensitiveHeaderNames = normalizeHeaderNames(source.forwardedSensitiveHeaderNames);
        copy.strippedSensitiveHeaderNames = normalizeHeaderNames(source.strippedSensitiveHeaderNames);
        return copy;
    }

    public String safeSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Redirect hop ").append(hopNumber > 0 ? hopNumber : "?");
        if (sourceMethod != null && !sourceMethod.isBlank()) {
            sb.append(" | ").append(sourceMethod);
        }
        if (statusCode > 0) {
            sb.append(" ").append(statusCode);
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            sb.append(" | ").append(sourceUrl);
        }
        if (targetUrl != null && !targetUrl.isBlank()) {
            sb.append(" -> ").append(targetUrl);
        }
        sb.append(" | followed=").append(followed);
        if (failureReason != null && !failureReason.isBlank()) {
            sb.append(" | reason=").append(failureReason);
        }
        if (!forwardedSensitiveHeaderNames.isEmpty()) {
            sb.append(" | forwarded=").append(forwardedSensitiveHeaderNames);
        }
        if (!strippedSensitiveHeaderNames.isEmpty()) {
            sb.append(" | stripped=").append(strippedSensitiveHeaderNames);
        }
        return sb.toString();
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
}
