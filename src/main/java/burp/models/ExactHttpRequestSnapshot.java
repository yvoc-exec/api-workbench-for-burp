package burp.models;

import burp.history.HistoryBodyTruncator;
import burp.payload.ManagedPayloadRef;
import burp.payload.PayloadSliceRef;
import burp.parser.HistoryRawHttpMessageParser;

import java.nio.charset.StandardCharsets;

public final class ExactHttpRequestSnapshot {
    public static final String BINARY_BODY_PLACEHOLDER_PREFIX = "[Binary exact body preserved";

    public ManagedPayloadRef payloadRef;
    public transient boolean payloadUnavailable;
    /** Legacy workspace/import compatibility only. New exact traffic is reference-backed. */
    public byte[] rawRequestBytes;
    public String serviceHost;
    public int servicePort;
    public boolean secure;
    public String httpVersion;
    public boolean pristine = true;
    public boolean binaryBody;
    public String sourceContext;
    public String invalidationReason;
    public String semanticFingerprint;

    /**
     * Transfers ownership of bytes already detached from Burp into the
     * canonical immutable exact-request owner. Callers must not mutate them.
     */
    public static ExactHttpRequestSnapshot fromOwnedTrafficBytes(byte[] ownedRawRequestBytes,
                                                                 long admittedMaximumBytes,
                                                                 String serviceHost,
                                                                 int servicePort,
                                                                 boolean secure,
                                                                 String sourceContext,
                                                                 String semanticFingerprint) {
        if (ownedRawRequestBytes == null || ownedRawRequestBytes.length == 0) {
            throw new IllegalArgumentException("Exact traffic requires request bytes.");
        }
        HistoryRawHttpMessageParser.RequestLayout layout =
                HistoryRawHttpMessageParser.inspectRequest(ownedRawRequestBytes);
        if (!layout.isTrustedRequest()) {
            throw new IllegalArgumentException("Exact traffic request is malformed.");
        }
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        snapshot.rawRequestBytes = ownedRawRequestBytes;
        snapshot.serviceHost = serviceHost;
        snapshot.servicePort = servicePort;
        snapshot.secure = secure;
        snapshot.httpVersion = layout.httpVersion();
        snapshot.pristine = true;
        snapshot.binaryBody = layout.bodyOffset() >= 0
                && layout.bodyOffset() < ownedRawRequestBytes.length
                && !isValidUtf8(ownedRawRequestBytes, layout.bodyOffset(),
                ownedRawRequestBytes.length - layout.bodyOffset());
        snapshot.sourceContext = sourceContext;
        snapshot.invalidationReason = "";
        snapshot.semanticFingerprint = semanticFingerprint;
        return snapshot;
    }

    public static ExactHttpRequestSnapshot fromManagedPayload(ManagedPayloadRef payloadRef,
                                                               String serviceHost,
                                                               int servicePort,
                                                               boolean secure,
                                                               String httpVersion,
                                                               boolean binaryBody,
                                                               String sourceContext,
                                                               String semanticFingerprint) {
        if (payloadRef == null) {
            throw new IllegalArgumentException("Exact traffic requires a managed payload reference.");
        }
        payloadRef.validate();
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        snapshot.payloadRef = payloadRef.copy();
        snapshot.rawRequestBytes = null;
        snapshot.serviceHost = serviceHost;
        snapshot.servicePort = servicePort;
        snapshot.secure = secure;
        snapshot.httpVersion = httpVersion;
        snapshot.pristine = true;
        snapshot.binaryBody = binaryBody;
        snapshot.sourceContext = sourceContext;
        snapshot.invalidationReason = "";
        snapshot.semanticFingerprint = semanticFingerprint;
        return snapshot;
    }

    public static ExactHttpRequestSnapshot copyOf(ExactHttpRequestSnapshot source) {
        return copyOf(source, true);
    }

    public static ExactHttpRequestSnapshot copySharingRawBytes(ExactHttpRequestSnapshot source) {
        return copyOf(source, false);
    }

    public static ExactHttpRequestSnapshot copyMetadataOnly(ExactHttpRequestSnapshot source) {
        ExactHttpRequestSnapshot copy = copyOf(source, false);
        if (copy != null) {
            copy.rawRequestBytes = null;
        }
        return copy;
    }

    private static ExactHttpRequestSnapshot copyOf(ExactHttpRequestSnapshot source, boolean cloneRawBytes) {
        if (source == null) {
            return null;
        }
        ExactHttpRequestSnapshot copy = new ExactHttpRequestSnapshot();
        copy.payloadRef = source.payloadRef != null ? source.payloadRef.copy() : null;
        copy.payloadUnavailable = source.payloadUnavailable;
        copy.rawRequestBytes = source.rawRequestBytes != null && cloneRawBytes
                ? source.rawRequestBytes.clone()
                : source.rawRequestBytes;
        copy.serviceHost = source.serviceHost;
        copy.servicePort = source.servicePort;
        copy.secure = source.secure;
        copy.httpVersion = source.httpVersion;
        copy.pristine = source.pristine;
        copy.binaryBody = source.binaryBody;
        copy.sourceContext = source.sourceContext;
        copy.invalidationReason = source.invalidationReason;
        copy.semanticFingerprint = source.semanticFingerprint;
        return copy;
    }

    public boolean hasManagedPayload() {
        return payloadRef != null && ManagedPayloadRef.isSha256(payloadRef.payloadId);
    }

    public boolean hasLegacyInlinePayload() {
        return rawRequestBytes != null && rawRequestBytes.length > 0;
    }

    public long payloadLength() {
        return hasManagedPayload() ? payloadRef.length
                : rawRequestBytes != null ? rawRequestBytes.length : 0L;
    }

    public String payloadSha256() {
        return hasManagedPayload() ? payloadRef.sha256
                : rawRequestBytes != null ? HistoryBodyTruncator.sha256Hex(rawRequestBytes) : "";
    }

    public static String managedBodyPlaceholder(PayloadSliceRef slice) {
        return managedBodyPlaceholder(slice, false);
    }

    public static String managedBodyPlaceholder(PayloadSliceRef slice, boolean unavailable) {
        if (slice == null || slice.payload == null) return "";
        return BINARY_BODY_PLACEHOLDER_PREFIX + ": " + slice.length
                + " bytes; SHA-256=" + slice.payload.sha256 + "; file-backed"
                + (unavailable ? "; unavailable" : "") + "]";
    }

    public static String binaryBodyPlaceholder(byte[] rawRequestBytes) {
        int bodyOffset = bodyOffset(rawRequestBytes);
        long length = bodyOffset >= 0 ? rawRequestBytes.length - bodyOffset : 0L;
        String hash = length > 0
                ? HistoryBodyTruncator.sha256Hex(rawRequestBytes, bodyOffset, (int) length)
                : "";
        return BINARY_BODY_PLACEHOLDER_PREFIX
                + ": " + length + " bytes; SHA-256=" + hash + "]";
    }

    public static String textBody(byte[] rawRequestBytes) {
        int bodyOffset = bodyOffset(rawRequestBytes);
        if (bodyOffset < 0 || bodyOffset >= rawRequestBytes.length) {
            return "";
        }
        return new String(rawRequestBytes, bodyOffset,
                rawRequestBytes.length - bodyOffset, StandardCharsets.UTF_8);
    }

    public static boolean isBinaryBodyPlaceholder(String text) {
        return text != null && text.startsWith(BINARY_BODY_PLACEHOLDER_PREFIX);
    }

    private static int bodyOffset(byte[] rawRequestBytes) {
        return HistoryRawHttpMessageParser.inspectRequest(rawRequestBytes).bodyOffset();
    }

    public static boolean isValidUtf8(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
            return false;
        }
        int end = offset + Math.max(0, length);
        for (int i = offset; i < end; i++) {
            int first = bytes[i] & 0xFF;
            if (first <= 0x7F) {
                continue;
            }
            int needed;
            int codePoint;
            if ((first & 0xE0) == 0xC0) {
                needed = 1;
                codePoint = first & 0x1F;
            } else if ((first & 0xF0) == 0xE0) {
                needed = 2;
                codePoint = first & 0x0F;
            } else if ((first & 0xF8) == 0xF0) {
                needed = 3;
                codePoint = first & 0x07;
            } else {
                return false;
            }
            if (i + needed >= end) {
                return false;
            }
            for (int j = 0; j < needed; j++) {
                int next = bytes[++i] & 0xFF;
                if ((next & 0xC0) != 0x80) {
                    return false;
                }
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
            if ((needed == 1 && codePoint < 0x80)
                    || (needed == 2 && codePoint < 0x800)
                    || (needed == 3 && codePoint < 0x10000)
                    || codePoint > 0x10FFFF
                    || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                return false;
            }
        }
        return true;
    }
}
