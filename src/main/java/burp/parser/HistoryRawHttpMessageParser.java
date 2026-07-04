package burp.parser;

import burp.history.HistoryHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HistoryRawHttpMessageParser {
    private HistoryRawHttpMessageParser() {
    }

    public static ParsedRawHttpMessage parseRequest(byte[] rawRequestBytes, String rawRequestText) {
        byte[] rawBytes = rawRequestBytes != null ? rawRequestBytes.clone() : null;
        if (rawBytes == null) {
            rawBytes = rawRequestText != null ? rawRequestText.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }
        String rawText = rawRequestText != null ? rawRequestText : new String(rawBytes, StandardCharsets.UTF_8);
        return parse(rawBytes, rawText, true);
    }

    static ParsedRawHttpMessage parse(byte[] rawBytes, String rawText, boolean request) {
        byte[] safeBytes = rawBytes != null ? rawBytes.clone() : new byte[0];
        String safeText = rawText != null ? rawText : new String(safeBytes, StandardCharsets.UTF_8);
        int crlfBoundary = indexOf(safeBytes, new byte[]{'\r', '\n', '\r', '\n'});
        int lfBoundary = crlfBoundary >= 0 ? -1 : indexOf(safeBytes, new byte[]{'\n', '\n'});
        int boundary = crlfBoundary >= 0 ? crlfBoundary : lfBoundary;
        String separator = crlfBoundary >= 0 ? "\r\n\r\n" : (lfBoundary >= 0 ? "\n\n" : "");
        int separatorLength = crlfBoundary >= 0 ? 4 : (lfBoundary >= 0 ? 2 : 0);
        byte[] headerBytes = boundary >= 0 ? slice(safeBytes, 0, boundary) : safeBytes.clone();
        byte[] bodyBytes = boundary >= 0 ? slice(safeBytes, boundary + separatorLength, safeBytes.length) : new byte[0];
        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        List<String> headerLines = splitLines(headerText);
        String startLine = !headerLines.isEmpty() ? headerLines.get(0).trim() : "";
        String method = "";
        String target = "";
        String httpVersion = "";
        String parseWarning = "";
        if (!startLine.isBlank()) {
            String[] parts = startLine.split("\\s+", 3);
            if (parts.length > 0) {
                method = parts[0];
            }
            if (parts.length > 1) {
                target = parts[1];
            }
            if (parts.length > 2) {
                httpVersion = parts[2];
            }
        } else {
            parseWarning = "MALFORMED_HTTP_REQUEST";
        }
        List<HistoryHeader> headers = new ArrayList<>();
        for (int i = 1; i < headerLines.size(); i++) {
            String line = headerLines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                if (parseWarning.isBlank()) {
                    parseWarning = "MALFORMED_HTTP_REQUEST";
                }
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.add(new HistoryHeader(name, value, false));
        }
        String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
        if (boundary < 0 && safeBytes.length > 0) {
            if (parseWarning.isBlank()) {
                parseWarning = "MISSING_HEADER_BODY_SEPARATOR";
            }
        }
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
                parseWarning
        );
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
            String parseWarning
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
        }
    }
}
