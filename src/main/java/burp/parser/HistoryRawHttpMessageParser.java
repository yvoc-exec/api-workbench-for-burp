package burp.parser;

import burp.history.HistoryHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class HistoryRawHttpMessageParser {
    private static final Pattern HTTP_VERSION_PATTERN = Pattern.compile("HTTP/\\d+(?:\\.\\d+)?");
    private static final int MAX_INSPECTED_HEADER_BYTES = 1024 * 1024;

    private HistoryRawHttpMessageParser() {
    }

    /** Inspects framing without owning request bytes or materializing body text. */
    public static RequestLayout inspectRequest(byte[] rawRequestBytes) {
        byte[] raw = rawRequestBytes != null ? rawRequestBytes : new byte[0];
        int crlfBoundary = indexOf(raw, new byte[]{'\r', '\n', '\r', '\n'});
        int lfBoundary = crlfBoundary >= 0 ? -1 : indexOf(raw, new byte[]{'\n', '\n'});
        int boundary = crlfBoundary >= 0 ? crlfBoundary : lfBoundary;
        int separatorLength = crlfBoundary >= 0 ? 4 : (lfBoundary >= 0 ? 2 : 0);
        int bodyOffset = boundary >= 0 ? boundary + separatorLength : -1;
        String separator = crlfBoundary >= 0 ? "\r\n\r\n" : (lfBoundary >= 0 ? "\n\n" : "");
        if (raw.length == 0) {
            return RequestLayout.invalid(separator, bodyOffset, "MISSING_RAW_REQUEST");
        }
        if (boundary < 0) {
            return RequestLayout.invalid(separator, -1, "MISSING_HEADER_BODY_SEPARATOR");
        }
        if (boundary > MAX_INSPECTED_HEADER_BYTES) {
            return RequestLayout.invalid(separator, bodyOffset, "HTTP_HEADER_SECTION_LIMIT");
        }

        String headerText = new String(raw, 0, boundary, StandardCharsets.ISO_8859_1);
        List<String> headerLines = splitLines(headerText);
        String startLine = !headerLines.isEmpty() ? headerLines.get(0).trim() : "";
        String[] parts = startLine.isBlank() ? new String[0] : startLine.split("\\s+");
        boolean validRequestLine = parts.length == 3
                && !parts[0].isBlank()
                && !parts[1].isBlank()
                && !parts[2].isBlank()
                && isHttpToken(parts[0])
                && HTTP_VERSION_PATTERN.matcher(parts[2]).matches();
        if (!validRequestLine) {
            return new RequestLayout(false, startLine, "", "", "", List.of(),
                    separator, boundary, separatorLength, bodyOffset, "MALFORMED_HTTP_REQUEST_LINE");
        }

        List<HistoryHeader> headers = new ArrayList<>();
        for (int i = 1; i < headerLines.size(); i++) {
            String line = headerLines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return new RequestLayout(false, startLine, "", "", "", List.of(),
                        separator, boundary, separatorLength, bodyOffset, "MALFORMED_HTTP_HEADER");
            }
            String name = line.substring(0, colon);
            if (name.isBlank() || !isHttpToken(name)) {
                return new RequestLayout(false, startLine, "", "", "", List.of(),
                        separator, boundary, separatorLength, bodyOffset, "MALFORMED_HTTP_HEADER");
            }
            headers.add(new HistoryHeader(name, trimOptionalWhitespace(line.substring(colon + 1)), false));
        }
        return new RequestLayout(true, startLine, parts[0], parts[1], parts[2], headers,
                separator, boundary, separatorLength, bodyOffset, "");
    }

    /** Defensive compatibility representation; hot paths use inspectRequest. */
    public static ParsedRawHttpMessage parseRequest(byte[] rawRequestBytes, String rawRequestText) {
        byte[] raw = rawRequestBytes != null && rawRequestBytes.length > 0
                ? rawRequestBytes
                : rawRequestText != null ? rawRequestText.getBytes(StandardCharsets.UTF_8) : new byte[0];
        RequestLayout layout = inspectRequest(raw);
        byte[] body = layout.bodyOffset() >= 0
                ? Arrays.copyOfRange(raw, layout.bodyOffset(), raw.length)
                : new byte[0];
        return new ParsedRawHttpMessage(
                raw,
                new String(raw, StandardCharsets.UTF_8),
                layout.separator(),
                layout.startLine(),
                layout.method(),
                layout.target(),
                layout.httpVersion(),
                layout.headers(),
                body,
                layout.trustedRequest() ? new String(body, StandardCharsets.UTF_8) : "",
                layout.parseWarning(),
                layout.trustedRequest(),
                layout.bodyOffset());
    }

    private static boolean isHttpToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean tokenChar = (ch >= '0' && ch <= '9')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || ch == '!' || ch == '#' || ch == '$' || ch == '%' || ch == '&'
                    || ch == '\'' || ch == '*' || ch == '+' || ch == '-' || ch == '.'
                    || ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~';
            if (!tokenChar) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || needle.length > haystack.length) {
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

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.replace("\r", "").split("\n", -1));
    }

    private static String trimOptionalWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    public record RequestLayout(
            boolean trustedRequest,
            String startLine,
            String method,
            String target,
            String httpVersion,
            List<HistoryHeader> headers,
            String separator,
            int headerLength,
            int separatorLength,
            int bodyOffset,
            String parseWarning) {
        public RequestLayout {
            startLine = startLine != null ? startLine : "";
            method = method != null ? method : "";
            target = target != null ? target : "";
            httpVersion = httpVersion != null ? httpVersion : "";
            headers = headers != null ? List.copyOf(headers) : List.of();
            separator = separator != null ? separator : "";
            parseWarning = parseWarning != null ? parseWarning : "";
        }

        static RequestLayout invalid(String separator, int bodyOffset, String warning) {
            return new RequestLayout(false, "", "", "", "", List.of(), separator,
                    bodyOffset >= 0 ? Math.max(0, bodyOffset - separator.length()) : -1,
                    separator.length(), bodyOffset, warning);
        }

        public int bodyLength(int rawLength) {
            return bodyOffset >= 0 && rawLength >= bodyOffset ? rawLength - bodyOffset : 0;
        }

        public boolean isTrustedRequest() {
            return trustedRequest;
        }
    }

    public record ParsedRawHttpMessage(
            byte[] rawBytes,
            String rawText,
            String separator,
            String startLine,
            String method,
            String target,
            String httpVersion,
            List<HistoryHeader> headers,
            byte[] bodyBytes,
            String bodyText,
            String parseWarning,
            boolean trustedRequest,
            int bodyOffset) {
        public ParsedRawHttpMessage {
            rawBytes = rawBytes != null ? rawBytes.clone() : new byte[0];
            headers = headers != null ? List.copyOf(headers) : List.of();
            bodyBytes = bodyBytes != null ? bodyBytes.clone() : new byte[0];
            rawText = rawText != null ? rawText : "";
            separator = separator != null ? separator : "";
            startLine = startLine != null ? startLine : "";
            method = method != null ? method : "";
            target = target != null ? target : "";
            httpVersion = httpVersion != null ? httpVersion : "";
            bodyText = bodyText != null ? bodyText : "";
            parseWarning = parseWarning != null ? parseWarning : "";
            bodyOffset = bodyOffset >= 0 ? bodyOffset : -1;
        }

        public boolean isTrustedRequest() {
            return trustedRequest;
        }
    }
}
