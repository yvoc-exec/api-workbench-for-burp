package burp.models;

import burp.history.HistoryBodyTruncator;

import java.nio.charset.StandardCharsets;

public final class ExactHttpRequestSnapshot {
    public static final String BINARY_BODY_PLACEHOLDER_PREFIX = "[Binary exact body preserved";

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
        if (rawRequestBytes == null || rawRequestBytes.length == 0) {
            return -1;
        }
        int separator = indexOf(rawRequestBytes, "\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        int separatorLength = 4;
        if (separator < 0) {
            separator = indexOf(rawRequestBytes, "\n\n".getBytes(StandardCharsets.UTF_8));
            separatorLength = 2;
        }
        if (separator < 0) {
            return -1;
        }
        return separator + separatorLength;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || haystack.length == 0 || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
