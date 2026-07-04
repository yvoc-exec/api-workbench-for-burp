package burp.history.evidence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class HistoryEvidenceRedactor {
    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> REQUEST_SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie");
    private static final Set<String> RESPONSE_SECRET_HEADERS = Set.of(
            "set-cookie", "proxy-authenticate", "www-authenticate");
    private static final Set<String> SECRET_QUERY_KEYS = Set.of(
            "access_token", "refresh_token", "id_token", "token", "api_key",
            "apikey", "client_secret", "secret", "password");

    public byte[] redactRequest(byte[] source) {
        return redactHttpMessage(source, true);
    }

    public byte[] redactResponse(byte[] source) {
        return redactHttpMessage(source, false);
    }

    public String redactMetadataText(String source) {
        if (source == null || source.isEmpty()) {
            return source != null ? source : "";
        }
        String[] lines = source.split("\\R", -1);
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf('=');
            int colon = line.indexOf(':');
            int split = separator >= 0 ? separator : colon;
            if (split > 0 && SECRET_QUERY_KEYS.contains(normalizeKey(line.substring(0, split)))) {
                out.append(line, 0, split + 1).append(REDACTED);
            } else {
                out.append(redactQueryParametersInText(line));
            }
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private byte[] redactHttpMessage(byte[] source, boolean request) {
        if (source == null || source.length == 0) {
            return new byte[0];
        }
        Boundary boundary = findBoundary(source);
        int headerEnd = boundary.index >= 0 ? boundary.index : source.length;
        byte[] headerBytes = java.util.Arrays.copyOfRange(source, 0, headerEnd);
        byte[] bodyBytes = boundary.index >= 0
                ? java.util.Arrays.copyOfRange(source, boundary.index + boundary.separatorLength, source.length)
                : new byte[0];
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String lineEnding = headers.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = headers.split("\\r?\\n", -1);
        StringBuilder redactedHeaders = new StringBuilder(headers.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && request) {
                line = redactRequestLine(line);
            } else if (i > 0) {
                line = redactHeaderLine(line, request);
            }
            redactedHeaders.append(line);
            if (i + 1 < lines.length) {
                redactedHeaders.append(lineEnding);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 32);
        out.writeBytes(redactedHeaders.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (boundary.index >= 0) {
            out.writeBytes(boundary.separatorBytes(source));
            out.writeBytes(bodyBytes);
        }
        return out.toByteArray();
    }

    private String redactRequestLine(String line) {
        if (line == null || line.isBlank()) {
            return line != null ? line : "";
        }
        int firstSpace = line.indexOf(' ');
        int lastSpace = line.lastIndexOf(' ');
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            return line;
        }
        String target = line.substring(firstSpace + 1, lastSpace);
        String redactedTarget = redactTargetQuery(target);
        return line.substring(0, firstSpace + 1) + redactedTarget + line.substring(lastSpace);
    }

    private String redactHeaderLine(String line, boolean request) {
        if (line == null) {
            return "";
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return line;
        }
        String name = line.substring(0, colon);
        String normalized = normalizeKey(name);
        Set<String> blocked = request ? REQUEST_SECRET_HEADERS : RESPONSE_SECRET_HEADERS;
        if (!blocked.contains(normalized)) {
            return line;
        }
        String whitespace = " ";
        int valueStart = colon + 1;
        while (valueStart < line.length() && (line.charAt(valueStart) == ' ' || line.charAt(valueStart) == '\t')) {
            valueStart++;
        }
        if (valueStart > colon + 1) {
            whitespace = line.substring(colon + 1, valueStart);
        }
        return name + ":" + whitespace + REDACTED;
    }

    private String redactTargetQuery(String target) {
        if (target == null) {
            return "";
        }
        int queryIndex = target.indexOf('?');
        if (queryIndex < 0 || queryIndex + 1 >= target.length()) {
            return target;
        }
        int fragmentIndex = target.indexOf('#', queryIndex + 1);
        String prefix = target.substring(0, queryIndex + 1);
        String query = fragmentIndex >= 0
                ? target.substring(queryIndex + 1, fragmentIndex)
                : target.substring(queryIndex + 1);
        String fragment = fragmentIndex >= 0 ? target.substring(fragmentIndex) : "";
        String[] parameters = query.split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            String parameter = parameters[i];
            int equals = parameter.indexOf('=');
            String rawKey = equals >= 0 ? parameter.substring(0, equals) : parameter;
            if (SECRET_QUERY_KEYS.contains(normalizeQueryKey(rawKey))) {
                parameters[i] = rawKey + (equals >= 0 ? "=" + REDACTED : "=" + REDACTED);
            }
        }
        return prefix + String.join("&", parameters) + fragment;
    }

    private String redactQueryParametersInText(String line) {
        if (line == null || !line.contains("?")) {
            return line != null ? line : "";
        }
        int queryIndex = line.indexOf('?');
        int end = line.indexOf(' ', queryIndex);
        String target = end >= 0 ? line.substring(queryIndex) : line.substring(queryIndex);
        String redacted = redactTargetQuery(target);
        return line.substring(0, queryIndex) + redacted + (end >= 0 ? line.substring(end) : "");
    }

    private Boundary findBoundary(byte[] source) {
        int crlf = indexOf(source, new byte[]{'\r', '\n', '\r', '\n'});
        if (crlf >= 0) {
            return new Boundary(crlf, 4);
        }
        int lf = indexOf(source, new byte[]{'\n', '\n'});
        return lf >= 0 ? new Boundary(lf, 2) : new Boundary(-1, 0);
    }

    private int indexOf(byte[] source, byte[] needle) {
        if (source == null || needle == null || needle.length == 0 || source.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= source.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (source[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private String normalizeKey(String key) {
        return key != null ? key.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeQueryKey(String key) {
        if (key == null) {
            return "";
        }
        String decoded = key.replace("%5F", "_").replace("%5f", "_");
        return decoded.trim().toLowerCase(Locale.ROOT);
    }

    private record Boundary(int index, int separatorLength) {
        byte[] separatorBytes(byte[] source) {
            return index >= 0 && source != null
                    ? java.util.Arrays.copyOfRange(source, index, index + separatorLength)
                    : new byte[0];
        }
    }
}
