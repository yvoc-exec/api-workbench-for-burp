package burp.history;

import burp.models.ApiRequest;
import burp.parser.HistoryRawHttpMessageParser;
import burp.parser.HistoryRawHttpMessageParser.ParsedRawHttpMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class HistoryBodyTruncator {
    public static final String REQUEST_BODY_LIMIT_REASON = "REQUEST_BODY_LIMIT";
    public static final String RAW_REQUEST_BODY_LIMIT_REASON = "RAW_REQUEST_BODY_LIMIT";
    public static final String RESPONSE_BODY_LIMIT_REASON = "RESPONSE_BODY_LIMIT";

    private HistoryBodyTruncator() {
    }

    public static HistoryEntry apply(HistoryEntry entry, HistoryRetentionPolicy policy) {
        if (entry == null) {
            return null;
        }

        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(policy);
        safePolicy.normalize();

        entry.ensureDefaults();
        truncateRequestSnapshot(entry.requestSnapshot, safePolicy);
        truncateResponseSnapshot(entry.responseSnapshot, safePolicy);

        entry.requestSizeBytes = entry.requestSnapshot != null && entry.requestSnapshot.rawRequestSent != null && entry.requestSnapshot.rawRequestSent.length > 0
                ? entry.requestSnapshot.rawRequestSent.length
                : entry.requestSnapshot != null
                ? entry.requestSnapshot.approximateSizeBytes()
                : 0L;
        entry.responseSizeBytes = entry.responseSnapshot != null && entry.responseSnapshot.body != null
                ? entry.responseSnapshot.body.length
                : 0L;
        return entry;
    }

    public static void normalizeSnapshotDefaults(HistoryEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.requestSnapshot != null) {
            ensureRequestDefaults(entry.requestSnapshot);
        }
        if (entry.responseSnapshot != null) {
            ensureResponseDefaults(entry.responseSnapshot);
        }
    }

    public static LinkedHashSet<String> normalizeTags(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return new LinkedHashSet<>();
        }
        String[] parts = commaSeparated.split(",");
        ArrayList<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            values.add(part);
        }
        return normalizeTags(values);
    }

    public static LinkedHashSet<String> normalizeTags(Iterable<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
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

    public static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static void truncateRequestSnapshot(HistoryRequestSnapshot snapshot, HistoryRetentionPolicy policy) {
        if (snapshot == null) {
            return;
        }

        ensureRequestDefaults(snapshot);

        byte[] authoredOriginal = snapshot.bodyAsAuthored != null ? snapshot.bodyAsAuthored.clone() : new byte[0];
        if (snapshot.bodyTruncated) {
            preserveTruncatedRequestMetadata(snapshot, authoredOriginal);
        } else {
            populateRequestBodyMetadata(snapshot, authoredOriginal);
            snapshot.truncationReason = "";
        }

        byte[] storedBody = authoredOriginal;
        if (authoredOriginal.length > policy.maxRequestBodyBytesPerEntry) {
            storedBody = truncateBytes(authoredOriginal, policy.maxRequestBodyBytesPerEntry);
            snapshot.bodyTruncated = true;
            snapshot.truncationReason = REQUEST_BODY_LIMIT_REASON;
        } else if (!snapshot.bodyTruncated) {
            snapshot.bodyTruncated = false;
            snapshot.truncationReason = "";
        } else if (snapshot.truncationReason == null || snapshot.truncationReason.isBlank()) {
            snapshot.truncationReason = REQUEST_BODY_LIMIT_REASON;
        }
        snapshot.bodyAsAuthored = storedBody;
        snapshot.storedBodyLength = storedBody.length;
        if (snapshot.bodyTruncated && snapshot.authoredRequest != null) {
            snapshot.authoredRequest = sanitizeAuthoredRequest(snapshot.authoredRequest, snapshot.bodyAsAuthored);
        }

        truncateRawRequest(snapshot, policy);
    }

    private static void truncateRawRequest(HistoryRequestSnapshot snapshot, HistoryRetentionPolicy policy) {
        boolean hasRawEvidence = (snapshot.rawRequestSent != null && snapshot.rawRequestSent.length > 0)
                || (snapshot.rawRequestSentText != null && !snapshot.rawRequestSentText.isBlank());
        if (!hasRawEvidence) {
            snapshot.rawBodyTruncated = false;
            snapshot.rawTruncationReason = "";
            snapshot.originalRawBodyLength = 0L;
            snapshot.storedRawBodyLength = 0L;
            if (snapshot.fullRawBodySha256 == null) {
                snapshot.fullRawBodySha256 = "";
            }
            return;
        }

        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(snapshot.rawRequestSent, snapshot.rawRequestSentText);
        byte[] rawBytes = parsed.rawBytes();
        byte[] bodyBytes = parsed.bodyBytes();

        snapshot.rawRequestSent = rawBytes;
        snapshot.rawRequestSentText = parsed.rawText();
        snapshot.parseWarning = firstNonBlank(snapshot.parseWarning, parsed.parseWarning());

        if (snapshot.rawBodyTruncated) {
            preserveTruncatedRawMetadata(snapshot, bodyBytes);
        } else {
            populateRawBodyMetadata(snapshot, bodyBytes);
            snapshot.rawTruncationReason = "";
        }

        byte[] storedBody = bodyBytes;
        if (bodyBytes.length > policy.maxRequestBodyBytesPerEntry) {
            storedBody = truncateBytes(bodyBytes, policy.maxRequestBodyBytesPerEntry);
            snapshot.rawBodyTruncated = true;
            snapshot.rawTruncationReason = RAW_REQUEST_BODY_LIMIT_REASON;
        } else if (!snapshot.rawBodyTruncated) {
            snapshot.rawBodyTruncated = false;
            snapshot.rawTruncationReason = "";
        } else if (snapshot.rawTruncationReason == null || snapshot.rawTruncationReason.isBlank()) {
            snapshot.rawTruncationReason = RAW_REQUEST_BODY_LIMIT_REASON;
        }

        snapshot.storedRawBodyLength = storedBody.length;
        if (snapshot.rawBodyTruncated) {
            snapshot.rawRequestSent = rebuildRawMessage(parsed, storedBody);
            snapshot.rawRequestSentText = new String(snapshot.rawRequestSent, StandardCharsets.UTF_8);
        }
    }

    private static void truncateResponseSnapshot(HistoryResponseSnapshot snapshot, HistoryRetentionPolicy policy) {
        if (snapshot == null) {
            return;
        }

        ensureResponseDefaults(snapshot);

        byte[] original = snapshot.body != null ? snapshot.body.clone() : new byte[0];
        if (snapshot.bodyTruncated) {
            preserveTruncatedResponseMetadata(snapshot, original);
        } else {
            populateResponseBodyMetadata(snapshot, original);
            snapshot.truncationReason = "";
        }

        byte[] storedBody = original;
        if (original.length > policy.maxResponseBodyBytesPerEntry) {
            storedBody = truncateBytes(original, policy.maxResponseBodyBytesPerEntry);
            snapshot.bodyTruncated = true;
            snapshot.truncationReason = RESPONSE_BODY_LIMIT_REASON;
        } else if (!snapshot.bodyTruncated) {
            snapshot.bodyTruncated = false;
            snapshot.truncationReason = "";
        } else if (snapshot.truncationReason == null || snapshot.truncationReason.isBlank()) {
            snapshot.truncationReason = RESPONSE_BODY_LIMIT_REASON;
        }
        snapshot.body = storedBody;
        snapshot.storedBodyLength = storedBody.length;
    }

    private static ApiRequest sanitizeAuthoredRequest(ApiRequest request, byte[] storedBody) {
        ApiRequest copy = request.applyTo(new ApiRequest());
        if (copy.body == null) {
            copy.body = new ApiRequest.Body();
        }
        copy.body.mode = "raw";
        copy.body.raw = storedBody != null ? new String(storedBody, StandardCharsets.UTF_8) : "";
        copy.body.graphql = null;
        copy.body.urlencoded = new ArrayList<>();
        copy.body.formdata = new ArrayList<>();
        return copy;
    }

    private static void ensureRequestDefaults(HistoryRequestSnapshot snapshot) {
        if (snapshot.headersAsAuthored == null) {
            snapshot.headersAsAuthored = new ArrayList<>();
        }
        if (snapshot.requestVariablesAsAuthored == null) {
            snapshot.requestVariablesAsAuthored = new java.util.LinkedHashMap<>();
        }
        if (snapshot.resolvedVariables == null) {
            snapshot.resolvedVariables = new java.util.LinkedHashMap<>();
        }
        if (snapshot.bodyAsAuthored == null) {
            String authoredBodyText = snapshot.authoredRequest != null ? snapshot.displayBodyText() : "";
            snapshot.bodyAsAuthored = authoredBodyText.getBytes(StandardCharsets.UTF_8);
        }
        if (snapshot.bodyTruncated == false && snapshot.storedBodyLength <= 0 && snapshot.bodyAsAuthored.length > 0) {
            snapshot.storedBodyLength = snapshot.bodyAsAuthored.length;
        }
        if (snapshot.originalBodyLength <= 0 && snapshot.bodyAsAuthored.length > 0) {
            snapshot.originalBodyLength = snapshot.bodyAsAuthored.length;
        }
        if (snapshot.fullBodySha256 == null || snapshot.fullBodySha256.isBlank()) {
            snapshot.fullBodySha256 = snapshot.bodyAsAuthored.length > 0 ? sha256Hex(snapshot.bodyAsAuthored) : "";
        }
        if (snapshot.truncationReason == null) {
            snapshot.truncationReason = "";
        }
        if (snapshot.rawTruncationReason == null) {
            snapshot.rawTruncationReason = "";
        }
        if (snapshot.parseWarning == null) {
            snapshot.parseWarning = "";
        }

        boolean hasRawEvidence = (snapshot.rawRequestSent != null && snapshot.rawRequestSent.length > 0)
                || (snapshot.rawRequestSentText != null && !snapshot.rawRequestSentText.isBlank());
        if (hasRawEvidence) {
            ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(snapshot.rawRequestSent, snapshot.rawRequestSentText);
            snapshot.rawRequestSent = parsed.rawBytes();
            snapshot.rawRequestSentText = parsed.rawText();
            snapshot.parseWarning = firstNonBlank(snapshot.parseWarning, parsed.parseWarning());
            byte[] bodyBytes = parsed.bodyBytes();
            if (snapshot.rawBodyTruncated) {
                if (snapshot.originalRawBodyLength <= 0 && bodyBytes.length > 0) {
                    snapshot.originalRawBodyLength = bodyBytes.length;
                }
                if (snapshot.storedRawBodyLength <= 0 && bodyBytes.length > 0) {
                    snapshot.storedRawBodyLength = bodyBytes.length;
                }
                if (snapshot.fullRawBodySha256 == null || snapshot.fullRawBodySha256.isBlank()) {
                    snapshot.fullRawBodySha256 = bodyBytes.length > 0 ? sha256Hex(bodyBytes) : "";
                }
                if (snapshot.rawTruncationReason == null || snapshot.rawTruncationReason.isBlank()) {
                    snapshot.rawTruncationReason = RAW_REQUEST_BODY_LIMIT_REASON;
                }
            } else {
                populateRawBodyMetadata(snapshot, bodyBytes);
            }
        } else {
            if (snapshot.originalRawBodyLength < 0) {
                snapshot.originalRawBodyLength = 0L;
            }
            if (snapshot.storedRawBodyLength < 0) {
                snapshot.storedRawBodyLength = 0L;
            }
            if (snapshot.fullRawBodySha256 == null) {
                snapshot.fullRawBodySha256 = "";
            }
        }
        if (snapshot.authoredRequest != null && snapshot.authoredRequest.body != null && snapshot.authoredRequest.body.raw == null) {
            snapshot.authoredRequest.body.raw = "";
        }
    }

    private static void ensureResponseDefaults(HistoryResponseSnapshot snapshot) {
        if (snapshot.headers == null) {
            snapshot.headers = new ArrayList<>();
        }
        if (snapshot.body == null) {
            snapshot.body = new byte[0];
        }
        if (snapshot.truncationReason == null) {
            snapshot.truncationReason = "";
        }
        if (snapshot.originalBodyLength <= 0 && snapshot.body.length > 0) {
            snapshot.originalBodyLength = snapshot.body.length;
        }
        if (snapshot.storedBodyLength <= 0 && snapshot.body.length > 0) {
            snapshot.storedBodyLength = snapshot.body.length;
        }
        if (snapshot.fullBodySha256 == null || snapshot.fullBodySha256.isBlank()) {
            snapshot.fullBodySha256 = snapshot.body.length > 0 ? sha256Hex(snapshot.body) : "";
        }
    }

    private static void populateRequestBodyMetadata(HistoryRequestSnapshot snapshot, byte[] original) {
        long originalLength = original != null ? original.length : 0L;
        snapshot.originalBodyLength = originalLength;
        snapshot.storedBodyLength = originalLength;
        snapshot.fullBodySha256 = originalLength > 0 ? sha256Hex(original) : "";
    }

    private static void preserveTruncatedRequestMetadata(HistoryRequestSnapshot snapshot, byte[] storedBody) {
        if (snapshot.originalBodyLength <= 0 && storedBody.length > 0) {
            snapshot.originalBodyLength = storedBody.length;
        }
        if (snapshot.storedBodyLength <= 0 && storedBody.length > 0) {
            snapshot.storedBodyLength = storedBody.length;
        }
        if (snapshot.fullBodySha256 == null || snapshot.fullBodySha256.isBlank()) {
            snapshot.fullBodySha256 = storedBody.length > 0 ? sha256Hex(storedBody) : "";
        }
        if (snapshot.truncationReason == null || snapshot.truncationReason.isBlank()) {
            snapshot.truncationReason = REQUEST_BODY_LIMIT_REASON;
        }
    }

    private static void populateRawBodyMetadata(HistoryRequestSnapshot snapshot, byte[] original) {
        long originalLength = original != null ? original.length : 0L;
        snapshot.originalRawBodyLength = originalLength;
        snapshot.fullRawBodySha256 = originalLength > 0 ? sha256Hex(original) : "";
        if (!snapshot.rawBodyTruncated) {
            snapshot.storedRawBodyLength = originalLength;
        }
    }

    private static void preserveTruncatedRawMetadata(HistoryRequestSnapshot snapshot, byte[] storedBody) {
        if (snapshot.originalRawBodyLength <= 0 && storedBody.length > 0) {
            snapshot.originalRawBodyLength = storedBody.length;
        }
        if (snapshot.storedRawBodyLength <= 0 && storedBody.length > 0) {
            snapshot.storedRawBodyLength = storedBody.length;
        }
        if (snapshot.fullRawBodySha256 == null || snapshot.fullRawBodySha256.isBlank()) {
            snapshot.fullRawBodySha256 = storedBody.length > 0 ? sha256Hex(storedBody) : "";
        }
        if (snapshot.rawTruncationReason == null || snapshot.rawTruncationReason.isBlank()) {
            snapshot.rawTruncationReason = RAW_REQUEST_BODY_LIMIT_REASON;
        }
    }

    private static void populateResponseBodyMetadata(HistoryResponseSnapshot snapshot, byte[] original) {
        long originalLength = original != null ? original.length : 0L;
        snapshot.originalBodyLength = originalLength;
        snapshot.storedBodyLength = originalLength;
        snapshot.fullBodySha256 = originalLength > 0 ? sha256Hex(original) : "";
    }

    private static void preserveTruncatedResponseMetadata(HistoryResponseSnapshot snapshot, byte[] storedBody) {
        if (snapshot.originalBodyLength <= 0 && storedBody.length > 0) {
            snapshot.originalBodyLength = storedBody.length;
        }
        if (snapshot.storedBodyLength <= 0 && storedBody.length > 0) {
            snapshot.storedBodyLength = storedBody.length;
        }
        if (snapshot.fullBodySha256 == null || snapshot.fullBodySha256.isBlank()) {
            snapshot.fullBodySha256 = storedBody.length > 0 ? sha256Hex(storedBody) : "";
        }
        if (snapshot.truncationReason == null || snapshot.truncationReason.isBlank()) {
            snapshot.truncationReason = RESPONSE_BODY_LIMIT_REASON;
        }
    }

    private static byte[] truncateBytes(byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0 || maxBytes <= 0 || bytes.length <= maxBytes) {
            return bytes != null ? bytes.clone() : new byte[0];
        }
        int length = (int) Math.min(bytes.length, maxBytes);
        byte[] out = new byte[length];
        System.arraycopy(bytes, 0, out, 0, length);
        return out;
    }

    private static byte[] rebuildRawMessage(ParsedRawHttpMessage parsed, byte[] bodyBytes) {
        if (parsed == null) {
            return bodyBytes != null ? bodyBytes.clone() : new byte[0];
        }
        byte[] originalRaw = parsed.rawBytes();
        byte[] originalBody = parsed.bodyBytes();
        int headerLength = Math.max(0, originalRaw.length - originalBody.length);
        byte[] out = new byte[headerLength + (bodyBytes != null ? bodyBytes.length : 0)];
        System.arraycopy(originalRaw, 0, out, 0, headerLength);
        if (bodyBytes != null && bodyBytes.length > 0) {
            System.arraycopy(bodyBytes, 0, out, headerLength, bodyBytes.length);
        }
        return out;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null ? second : "";
    }
}
