package burp.utils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Test helper that parses raw HTTP/1.1 request bytes into structured parts.
 * Splits at first double-CRLF; everything before is headers, after is body.
 */
public class RawRequestParser {
    public final String requestLine;
    public final Map<String, String> headers;
    public final byte[] body;

    private RawRequestParser(String requestLine, Map<String, String> headers, byte[] body) {
        this.requestLine = requestLine;
        this.headers = headers;
        this.body = body;
    }

    public static RawRequestParser parse(byte[] raw) {
        // Find the empty line that separates headers from body
        int split = -1;
        for (int i = 0; i < raw.length - 3; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }

        byte[] headerBytes;
        byte[] bodyBytes;
        if (split >= 0) {
            headerBytes = Arrays.copyOfRange(raw, 0, split);
            bodyBytes = Arrays.copyOfRange(raw, split + 4, raw.length);
        } else {
            // No body
            headerBytes = raw;
            bodyBytes = new byte[0];
        }

        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        String[] lines = headerText.split("\r\n");
        String requestLine = lines.length > 0 ? lines[0] : "";

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }

        return new RawRequestParser(requestLine, headers, bodyBytes);
    }

    public String method() {
        int sp = requestLine.indexOf(' ');
        return sp > 0 ? requestLine.substring(0, sp) : "";
    }

    public String path() {
        int first = requestLine.indexOf(' ');
        if (first < 0) return "";
        int second = requestLine.indexOf(' ', first + 1);
        return second > first ? requestLine.substring(first + 1, second) : "";
    }

    public boolean hasHeader(String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public String headerValue(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    public int contentLength() {
        String cl = headerValue("Content-Length");
        if (cl == null) return -1;
        try {
            return Integer.parseInt(cl);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
