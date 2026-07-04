package burp.parser;

import burp.history.HistoryHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class HistoryRawHttpMessageParser {
    private static final Pattern HTTP_VERSION_PATTERN = Pattern.compile("HTTP/\\d+(?:\\.\\d+)?");

    private HistoryRawHttpMessageParser() {
    }

    public static ParsedRawHttpMessage parseRequest(byte[] rawRequestBytes, String rawRequestText) {
        byte[] rawBytes = rawRequestBytes != null && rawRequestBytes.length > 0
                ? rawRequestBytes.clone()
                : rawRequestText != null
                ? rawRequestText.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        String rawText = new String(rawBytes, StandardCharsets.UTF_8);
        return parse(rawBytes, rawText, true);
    }

    static ParsedRawHttpMessage parse(byte[] rawBytes, String rawText, boolean request) {
        byte[] safeBytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        String safeText = new String(safeBytes, StandardCharsets.UTF_8);
        int crlfBoundary = indexOf(safeBytes, new byte[]{'\r', '\n', '\r', '\n'});
        int lfBoundary = crlfBoundary >= 0 ? -1 : indexOf(safeBytes, new byte[]{'\n', '\n'});
        int boundary = crlfBoundary >= 0 ? crlfBoundary : lfBoundary;
        String separator = crlfBoundary >= 0 ? "\r\n\r\n" : (lfBoundary >= 0 ? "\n\n" : "");
        int separatorLength = crlfBoundary >= 0 ? 4 : (lfBoundary >= 0 ? 2 : 0);
        int bodyOffset = boundary >= 0 ? boundary + separatorLength : -1;
        byte[] headerBytes = boundary >= 0 ? slice(safeBytes, 0, boundary) : safeBytes.clone();
        byte[] bodyBytes = bodyOffset >= 0 ? slice(safeBytes, bodyOffset, safeBytes.length) : new byte[0];
        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        List<String> headerLines = splitLines(headerText);
        String startLine = !headerLines.isEmpty() ? headerLines.get(0).trim() : "";
        String method = "";
        String target = "";
        String httpVersion = "";
        String parseWarning = "";
        boolean trustedRequest = false;
        List<HistoryHeader> headers = new ArrayList<>();

        if (request) {
            if (boundary < 0 && safeBytes.length > 0) {
                parseWarning = "MISSING_HEADER_BODY_SEPARATOR";
            } else {
                String[] parts = startLine.isBlank() ? new String[0] : startLine.split("\\s+");
                boolean validRequestLine = parts.length == 3
                        && !parts[0].isBlank()
                        && !parts[1].isBlank()
                        && !parts[2].isBlank()
                        && isHttpToken(parts[0])
                        && HTTP_VERSION_PATTERN.matcher(parts[2]).matches();
                if (!validRequestLine) {
                    parseWarning = "MALFORMED_HTTP_REQUEST_LINE";
                } else {
                    boolean validHeaders = true;
                    for (int i = 1; i < headerLines.size(); i++) {
                        String line = headerLines.get(i);
                        if (line == null || line.isBlank()) {
                            continue;
                        }
                        int colon = line.indexOf(':');
                        if (colon <= 0) {
                            validHeaders = false;
                            break;
                        }
                        String name = line.substring(0, colon).trim();
                        if (name.isBlank() || !isHttpToken(name)) {
                            validHeaders = false;
                            break;
                        }
                        String value = line.substring(colon + 1).trim();
                        headers.add(new HistoryHeader(name, value, false));
                    }
                    if (!validHeaders) {
                        headers = new ArrayList<>();
                        parseWarning = "MALFORMED_HTTP_HEADER";
                    } else {
                        method = parts[0];
                        target = parts[1];
                        httpVersion = parts[2];
                        trustedRequest = true;
                    }
                }
            }
        }

        String bodyText = trustedRequest ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
        return new ParsedRawHttpMessage(
                safeBytes,
                safeText,
                separator,
                startLine,
                method,
                target,
                httpVersion,
                headers,
                bodyBytes,
                bodyText,
                parseWarning,
                trustedRequest,
                bodyOffset
        );
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
        if (haystack == null || needle == null || haystack.length == 0 || needle.length == 0 || needle.length > haystack.length) {
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

    private static byte[] slice(byte[] source, int start, int end) {
        if (source == null || start < 0 || end < start || start >= source.length) {
            return new byte[0];
        }
        int actualEnd = Math.min(end, source.length);
        byte[] out = new byte[Math.max(0, actualEnd - start)];
        System.arraycopy(source, start, out, 0, out.length);
        return out;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] split = text.replace("\r", "").split("\n", -1);
        for (String line : split) {
            lines.add(line);
        }
        return lines;
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
            int bodyOffset
    ) {
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
