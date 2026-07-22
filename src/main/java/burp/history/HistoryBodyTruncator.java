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
    public static final String RAW_REQUEST_EVIDENCE_LIMIT_REASON = "RAW_REQUEST_EVIDENCE_LIMIT";
    public static final String RESPONSE_BODY_LIMIT_REASON = "RESPONSE_BODY_LIMIT";
    public static final String LEGACY_HISTORY_BUDGET_COMPACTION = "LEGACY_HISTORY_BUDGET_COMPACTION";
    public static final int LEGACY_PREVIEW_MAX_BYTES = 4 * 1024;

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
        truncateRedirectHops(entry.redirectHops, safePolicy);

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

    /**
     * Reduces payload previews on a defensive legacy entry without touching its
     * identity, analyst metadata, or execution metadata. This is intentionally
     * separate from ordinary per-entry truncation: callers invoke it only while
     * migrating a legacy workspace that cannot satisfy the hard total budget.
     */
    public static boolean compactLegacyPayloads(HistoryEntry entry) {
        return compactLegacyPayloads(entry, LEGACY_PREVIEW_MAX_BYTES);
    }

    /**
     * Compacts legacy payload fields in a fixed order. The requested preview is
     * capped at 4 KiB and may be lowered by the migration planner when 4 KiB
     * previews are still too large. Existing original lengths and full hashes
     * are preserved, and already smaller previews are never expanded.
     */
    public static boolean compactLegacyPayloads(HistoryEntry entry, long maxPreviewBytes) {
        return compactLegacyPayloads(entry, maxPreviewBytes, 0L);
    }

    /**
     * Compacts payloads only until the entry reaches the requested retained-size
     * target. The store derives that target from the remaining aggregate budget,
     * allowing the fixed field order to avoid discarding later payloads once the
     * migration already fits.
     */
    public static boolean compactLegacyPayloads(
            HistoryEntry entry,
            long maxPreviewBytes,
            long targetStoredBytes) {
        if (entry == null) {
            return false;
        }
        int previewLimit = (int) Math.max(0L, Math.min(LEGACY_PREVIEW_MAX_BYTES, maxPreviewBytes));
        long target = Math.max(0L, targetStoredBytes);
        boolean changed = false;

        // Fixed order: main response, redirect responses (last to first), raw
        // request, redirect raw requests (last to first), authored request.
        if (entry.estimatedStoredBytes() > target) {
            changed |= compactLegacyResponse(entry.responseSnapshot, previewLimit);
        }
        if (entry.redirectHops != null) {
            for (int i = entry.redirectHops.size() - 1; i >= 0; i--) {
                if (entry.estimatedStoredBytes() <= target) {
                    break;
                }
                changed |= compactLegacyRedirectResponse(entry.redirectHops.get(i), previewLimit);
            }
        }
        if (entry.estimatedStoredBytes() > target) {
            changed |= compactLegacyRawRequest(entry.requestSnapshot, previewLimit);
        }
        if (entry.redirectHops != null) {
            for (int i = entry.redirectHops.size() - 1; i >= 0; i--) {
                if (entry.estimatedStoredBytes() <= target) {
                    break;
                }
                changed |= compactLegacyRedirectRequest(entry.redirectHops.get(i), previewLimit);
            }
        }
        if (entry.estimatedStoredBytes() > target) {
            changed |= compactLegacyAuthoredRequest(entry.requestSnapshot, previewLimit);
        }

        if (changed) {
            entry.legacyBudgetCompacted = true;
            entry.requestSizeBytes = entry.requestSnapshot != null
                    && entry.requestSnapshot.rawRequestSent != null
                    && entry.requestSnapshot.rawRequestSent.length > 0
                    ? entry.requestSnapshot.rawRequestSent.length
                    : entry.requestSnapshot != null ? entry.requestSnapshot.approximateSizeBytes() : 0L;
            entry.responseSizeBytes = entry.responseSnapshot != null && entry.responseSnapshot.body != null
                    ? entry.responseSnapshot.body.length
                    : 0L;
        }
        return changed;
    }

    private static boolean compactLegacyResponse(HistoryResponseSnapshot snapshot, int limit) {
        if (snapshot == null) {
            return false;
        }
        byte[] current = snapshot.body != null ? snapshot.body : new byte[0];
        if (current.length <= limit) {
            return false;
        }
        snapshot.originalBodyLength = preserveOriginalLength(snapshot.originalBodyLength, current.length);
        snapshot.fullBodySha256 = preserveOriginalHash(snapshot.fullBodySha256, current);
        snapshot.body = legacyPreview(current, limit);
        snapshot.storedBodyLength = snapshot.body.length;
        snapshot.bodyTruncated = true;
        snapshot.truncationReason = LEGACY_HISTORY_BUDGET_COMPACTION;
        return true;
    }

    private static boolean compactLegacyRedirectResponse(burp.models.RedirectHop hop, int limit) {
        if (hop == null) {
            return false;
        }
        byte[] current = hop.responseBody != null ? hop.responseBody : new byte[0];
        if (current.length <= limit) {
            return false;
        }
        hop.originalResponseBodyLength = preserveOriginalLength(hop.originalResponseBodyLength, current.length);
        hop.fullResponseBodySha256 = preserveOriginalHash(hop.fullResponseBodySha256, current);
        hop.responseBody = legacyPreview(current, limit);
        hop.storedResponseBodyLength = hop.responseBody.length;
        hop.responseBodyTruncated = true;
        hop.responseTruncationReason = LEGACY_HISTORY_BUDGET_COMPACTION;
        return true;
    }

    private static boolean compactLegacyRawRequest(HistoryRequestSnapshot snapshot, int limit) {
        if (snapshot == null) {
            return false;
        }
        byte[] currentRaw = authoritativeRawRequestBytes(snapshot.rawRequestSent, snapshot.rawRequestSentText);
        if (currentRaw.length == 0) {
            return false;
        }
        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(currentRaw, null);
        byte[] currentPayload = parsed.bodyOffset() >= 0 ? parsed.bodyBytes() : currentRaw;
        if (currentPayload.length <= limit) {
            return false;
        }
        snapshot.originalRawBodyLength = preserveOriginalLength(snapshot.originalRawBodyLength, currentPayload.length);
        snapshot.fullRawBodySha256 = preserveOriginalHash(snapshot.fullRawBodySha256, currentPayload);
        byte[] preview = legacyPreview(currentPayload, limit);
        byte[] storedRaw = parsed.bodyOffset() >= 0 ? rebuildRawMessage(parsed, preview) : preview;
        snapshot.rawRequestSent = storedRaw;
        snapshot.rawRequestSentText = new String(storedRaw, StandardCharsets.UTF_8);
        snapshot.storedRawBodyLength = preview.length;
        snapshot.rawBodyTruncated = true;
        snapshot.rawTruncationReason = LEGACY_HISTORY_BUDGET_COMPACTION;
        return true;
    }

    private static boolean compactLegacyRedirectRequest(burp.models.RedirectHop hop, int limit) {
        if (hop == null) {
            return false;
        }
        byte[] currentRaw = authoritativeRawRequestBytes(hop.rawRequestBytes, hop.rawRequestText);
        if (currentRaw.length == 0) {
            return false;
        }
        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(currentRaw, null);
        byte[] currentPayload = parsed.bodyOffset() >= 0 ? parsed.bodyBytes() : currentRaw;
        if (currentPayload.length <= limit) {
            return false;
        }
        hop.originalRawRequestBodyLength = preserveOriginalLength(hop.originalRawRequestBodyLength, currentPayload.length);
        hop.fullRawRequestBodySha256 = preserveOriginalHash(hop.fullRawRequestBodySha256, currentPayload);
        byte[] preview = legacyPreview(currentPayload, limit);
        byte[] storedRaw = parsed.bodyOffset() >= 0 ? rebuildRawMessage(parsed, preview) : preview;
        hop.rawRequestBytes = storedRaw;
        hop.rawRequestText = new String(storedRaw, StandardCharsets.UTF_8);
        hop.storedRawRequestBodyLength = preview.length;
        hop.rawRequestBodyTruncated = true;
        hop.rawRequestTruncationReason = LEGACY_HISTORY_BUDGET_COMPACTION;
        return true;
    }

    private static boolean compactLegacyAuthoredRequest(HistoryRequestSnapshot snapshot, int limit) {
        if (snapshot == null) {
            return false;
        }
        byte[] current = snapshot.bodyAsAuthored != null ? snapshot.bodyAsAuthored : new byte[0];
        if (current.length <= limit) {
            return false;
        }
        snapshot.originalBodyLength = preserveOriginalLength(snapshot.originalBodyLength, current.length);
        snapshot.fullBodySha256 = preserveOriginalHash(snapshot.fullBodySha256, current);
        snapshot.bodyAsAuthored = legacyPreview(current, limit);
        snapshot.storedBodyLength = snapshot.bodyAsAuthored.length;
        snapshot.bodyTruncated = true;
        snapshot.truncationReason = LEGACY_HISTORY_BUDGET_COMPACTION;
        if (snapshot.authoredRequest != null) {
            snapshot.authoredRequest = sanitizeAuthoredRequest(snapshot.authoredRequest, snapshot.bodyAsAuthored);
        }
        return true;
    }

    private static byte[] legacyPreview(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0) {
            return new byte[0];
        }
        int length = Math.max(0, Math.min(bytes.length, limit));
        byte[] preview = new byte[length];
        if (length > 0) {
            System.arraycopy(bytes, 0, preview, 0, length);
        }
        return preview;
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
            if (!snapshot.rawBodyTruncated) {
                snapshot.rawTruncationReason = "";
                snapshot.originalRawBodyLength = 0L;
            } else if (snapshot.rawTruncationReason == null || snapshot.rawTruncationReason.isBlank()) {
                snapshot.rawTruncationReason = RAW_REQUEST_EVIDENCE_LIMIT_REASON;
            }
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
            preserveTruncatedRawMetadata(snapshot, parsed.bodyOffset() >= 0 ? bodyBytes : rawBytes);
        } else {
            populateRawBodyMetadata(snapshot, parsed.bodyOffset() >= 0 ? bodyBytes : rawBytes);
            snapshot.rawTruncationReason = "";
        }

        if (parsed.bodyOffset() >= 0) {
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
            return;
        }

        byte[] storedEvidence = rawBytes;
        if (rawBytes.length > policy.maxRequestBodyBytesPerEntry) {
            storedEvidence = truncateBytes(rawBytes, policy.maxRequestBodyBytesPerEntry);
            snapshot.rawBodyTruncated = true;
            snapshot.rawTruncationReason = RAW_REQUEST_EVIDENCE_LIMIT_REASON;
        } else if (!snapshot.rawBodyTruncated) {
            snapshot.rawBodyTruncated = false;
            snapshot.rawTruncationReason = "";
        } else if (snapshot.rawTruncationReason == null || snapshot.rawTruncationReason.isBlank()) {
            snapshot.rawTruncationReason = RAW_REQUEST_EVIDENCE_LIMIT_REASON;
        }
        snapshot.storedRawBodyLength = storedEvidence.length;
        if (snapshot.rawBodyTruncated) {
            snapshot.rawRequestSent = storedEvidence;
            snapshot.rawRequestSentText = new String(snapshot.rawRequestSent, StandardCharsets.UTF_8);
        }
    }

    private static void truncateRedirectHops(java.util.List<burp.models.RedirectHop> hops, HistoryRetentionPolicy policy) {
        if (hops == null || hops.isEmpty()) {
            return;
        }
        for (burp.models.RedirectHop hop : hops) {
            truncateRedirectHop(hop, policy);
        }
    }

    private static void truncateRedirectHop(burp.models.RedirectHop hop, HistoryRetentionPolicy policy) {
        if (hop == null) {
            return;
        }
        truncateRedirectHopRawRequest(hop, policy);
        truncateRedirectHopResponse(hop, policy);
    }

    private static void truncateRedirectHopRawRequest(burp.models.RedirectHop hop, HistoryRetentionPolicy policy) {
        byte[] authoritativeRaw = authoritativeRawRequestBytes(hop.rawRequestBytes, hop.rawRequestText);
        if (authoritativeRaw.length == 0) {
            hop.rawRequestBytes = null;
            hop.rawRequestText = "";
            if (!hop.rawRequestBodyTruncated) {
                hop.originalRawRequestBodyLength = 0L;
            } else if (hop.rawRequestTruncationReason == null || hop.rawRequestTruncationReason.isBlank()) {
                hop.rawRequestTruncationReason = RAW_REQUEST_EVIDENCE_LIMIT_REASON;
            }
            hop.storedRawRequestBodyLength = 0L;
            if (hop.fullRawRequestBodySha256 == null) {
                hop.fullRawRequestBodySha256 = "";
            }
            if (hop.rawRequestTruncationReason == null) {
                hop.rawRequestTruncationReason = "";
            }
            return;
        }

        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(authoritativeRaw, null);
        byte[] storedRaw = authoritativeRaw;
        String reason = "";

        if (parsed.bodyOffset() >= 0) {
            byte[] storedBody = parsed.bodyBytes();
            long originalLength = preserveOriginalLength(hop.originalRawRequestBodyLength, storedBody.length);
            String fullHash = preserveOriginalHash(hop.fullRawRequestBodySha256, storedBody);
            if (storedBody.length > policy.maxRequestBodyBytesPerEntry) {
                storedBody = truncateBytes(storedBody, policy.maxRequestBodyBytesPerEntry);
                reason = RAW_REQUEST_BODY_LIMIT_REASON;
            } else if (hop.rawRequestBodyTruncated) {
                reason = firstNonBlank(hop.rawRequestTruncationReason, RAW_REQUEST_BODY_LIMIT_REASON);
            }
            storedRaw = rebuildRawMessage(parsed, storedBody);
            hop.rawRequestBodyTruncated = hop.rawRequestBodyTruncated || storedBody.length < parsed.bodyBytes().length;
            hop.originalRawRequestBodyLength = originalLength;
            hop.storedRawRequestBodyLength = storedBody.length;
            hop.fullRawRequestBodySha256 = fullHash;
            hop.rawRequestTruncationReason = hop.rawRequestBodyTruncated ? reason : "";
        } else {
            long originalLength = preserveOriginalLength(hop.originalRawRequestBodyLength, authoritativeRaw.length);
            String fullHash = preserveOriginalHash(hop.fullRawRequestBodySha256, authoritativeRaw);
            if (authoritativeRaw.length > policy.maxRequestBodyBytesPerEntry) {
                storedRaw = truncateBytes(authoritativeRaw, policy.maxRequestBodyBytesPerEntry);
                reason = RAW_REQUEST_EVIDENCE_LIMIT_REASON;
            } else if (hop.rawRequestBodyTruncated) {
                reason = firstNonBlank(hop.rawRequestTruncationReason, RAW_REQUEST_EVIDENCE_LIMIT_REASON);
            }
            hop.rawRequestBodyTruncated = hop.rawRequestBodyTruncated || storedRaw.length < authoritativeRaw.length;
            hop.originalRawRequestBodyLength = originalLength;
            hop.storedRawRequestBodyLength = storedRaw.length;
            hop.fullRawRequestBodySha256 = fullHash;
            hop.rawRequestTruncationReason = hop.rawRequestBodyTruncated ? reason : "";
        }

        hop.rawRequestBytes = storedRaw.length > 0 ? storedRaw.clone() : null;
        hop.rawRequestText = new String(storedRaw, StandardCharsets.UTF_8);
    }

    private static void truncateRedirectHopResponse(burp.models.RedirectHop hop, HistoryRetentionPolicy policy) {
        byte[] storedBody = hop.responseBody != null ? hop.responseBody.clone() : new byte[0];
        if (storedBody.length == 0) {
            hop.responseBody = null;
            if (!hop.responseBodyTruncated) {
                hop.originalResponseBodyLength = 0L;
            } else if (hop.responseTruncationReason == null || hop.responseTruncationReason.isBlank()) {
                hop.responseTruncationReason = RESPONSE_BODY_LIMIT_REASON;
            }
            hop.storedResponseBodyLength = 0L;
            if (hop.fullResponseBodySha256 == null) {
                hop.fullResponseBodySha256 = "";
            }
            if (hop.responseTruncationReason == null) {
                hop.responseTruncationReason = "";
            }
            return;
        }

        hop.originalResponseBodyLength = preserveOriginalLength(hop.originalResponseBodyLength, storedBody.length);
        hop.fullResponseBodySha256 = preserveOriginalHash(hop.fullResponseBodySha256, storedBody);
        if (storedBody.length > policy.maxResponseBodyBytesPerEntry) {
            storedBody = truncateBytes(storedBody, policy.maxResponseBodyBytesPerEntry);
            hop.responseBodyTruncated = true;
            hop.responseTruncationReason = RESPONSE_BODY_LIMIT_REASON;
        } else if (hop.responseBodyTruncated) {
            hop.responseTruncationReason = firstNonBlank(hop.responseTruncationReason, RESPONSE_BODY_LIMIT_REASON);
        } else {
            hop.responseTruncationReason = "";
        }
        hop.responseBody = storedBody;
        hop.storedResponseBodyLength = storedBody.length;
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
        if (parsed.bodyOffset() < 0) {
            return originalRaw.clone();
        }
        int headerLength = Math.min(Math.max(parsed.bodyOffset(), 0), originalRaw.length);
        byte[] out = new byte[headerLength + (bodyBytes != null ? bodyBytes.length : 0)];
        System.arraycopy(originalRaw, 0, out, 0, headerLength);
        if (bodyBytes != null && bodyBytes.length > 0) {
            System.arraycopy(bodyBytes, 0, out, headerLength, bodyBytes.length);
        }
        return out;
    }

    private static byte[] authoritativeRawRequestBytes(byte[] rawRequestBytes, String rawRequestText) {
        if (rawRequestBytes != null && rawRequestBytes.length > 0) {
            return rawRequestBytes.clone();
        }
        if (rawRequestText != null && !rawRequestText.isBlank()) {
            return rawRequestText.getBytes(StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    private static long preserveOriginalLength(long existingOriginalLength, int currentStoredLength) {
        if (existingOriginalLength > 0) {
            return Math.max(existingOriginalLength, currentStoredLength);
        }
        return Math.max(currentStoredLength, 0);
    }

    private static String preserveOriginalHash(String existingHash, byte[] originalBytes) {
        if (existingHash != null && !existingHash.isBlank()) {
            return existingHash;
        }
        return originalBytes != null && originalBytes.length > 0 ? sha256Hex(originalBytes) : "";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null ? second : "";
    }
}
