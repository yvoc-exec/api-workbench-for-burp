package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

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
    public boolean rawRequestBodyTruncated;
    public long originalRawRequestBodyLength;
    public long storedRawRequestBodyLength;
    public String fullRawRequestBodySha256 = "";
    public String rawRequestTruncationReason = "";
    public String responseHeadersText;
    public byte[] responseBody;
    public boolean responseBodyTruncated;
    public long originalResponseBodyLength;
    public long storedResponseBodyLength;
    public String fullResponseBodySha256 = "";
    public String responseTruncationReason = "";
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
        copy.canonicalizeRawEvidence();
        copy.rawRequestBodyTruncated = source.rawRequestBodyTruncated;
        copy.originalRawRequestBodyLength = source.originalRawRequestBodyLength;
        copy.storedRawRequestBodyLength = source.storedRawRequestBodyLength;
        copy.fullRawRequestBodySha256 = source.fullRawRequestBodySha256;
        copy.rawRequestTruncationReason = source.rawRequestTruncationReason;
        copy.responseHeadersText = source.responseHeadersText;
        copy.responseBody = source.responseBody != null ? source.responseBody.clone() : null;
        copy.responseBodyTruncated = source.responseBodyTruncated;
        copy.originalResponseBodyLength = source.originalResponseBodyLength;
        copy.storedResponseBodyLength = source.storedResponseBodyLength;
        copy.fullResponseBodySha256 = source.fullResponseBodySha256;
        copy.responseTruncationReason = source.responseTruncationReason;
        copy.followed = source.followed;
        copy.failureReason = source.failureReason;
        copy.forwardedSensitiveHeaderNames = normalizeHeaderNames(source.forwardedSensitiveHeaderNames);
        copy.strippedSensitiveHeaderNames = normalizeHeaderNames(source.strippedSensitiveHeaderNames);
        return copy;
    }

    public String preferredRawRequestText() {
        if (rawRequestBytes != null && rawRequestBytes.length > 0) {
            return new String(rawRequestBytes, StandardCharsets.UTF_8);
        }
        return rawRequestText != null ? rawRequestText : "";
    }

    /** Applies the same bytes-first ownership rule as History requests. */
    public void canonicalizeRawEvidence() {
        if (rawRequestBytes != null && rawRequestBytes.length > 0) {
            rawRequestText = null;
            return;
        }
        rawRequestBytes = null;
        if (rawRequestText == null || rawRequestText.isBlank()) {
            rawRequestText = null;
            return;
        }
        byte[] encoded = rawRequestText.getBytes(StandardCharsets.UTF_8);
        if (rawRequestText.equals(new String(encoded, StandardCharsets.UTF_8))) {
            rawRequestBytes = encoded;
            rawRequestText = null;
        }
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
