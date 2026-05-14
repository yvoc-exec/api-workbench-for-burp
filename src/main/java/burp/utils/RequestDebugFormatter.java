package burp.utils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats raw HTTP request bytes for debug logging, masking sensitive tokens.
 */
public class RequestDebugFormatter {
    private static final int MAX_BODY_PREVIEW = 4096;

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(Bearer\\s+)([A-Za-z0-9_\\-\\.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLIENT_SECRET_BODY_PATTERN = Pattern.compile(
            "(client_secret\\s*=\\s*)([^&\\s\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASIC_AUTH_PATTERN = Pattern.compile(
            "(Authorization\\s*:\\s*Basic\\s+)([A-Za-z0-9+/=]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_BODY_PATTERN = Pattern.compile(
            "(password\\s*=\\s*)([^&\\s\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    public static String format(byte[] rawRequest, String context, String requestName) {
        String raw = new String(rawRequest, StandardCharsets.UTF_8);
        String masked = maskSecrets(raw);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Debug Raw Request [").append(context).append("] ===\n");
        sb.append("Name: ").append(requestName != null ? requestName : "(unnamed)").append("\n");

        int emptyLine = findEmptyLine(masked);
        if (emptyLine > 0) {
            String headers = masked.substring(0, emptyLine);
            sb.append(headers).append("\n");

            if (emptyLine + 2 < masked.length()) {
                String body = masked.substring(emptyLine + 2);
                int bodyBytes = raw.length() - (emptyLine + 2);
                sb.append("\n[Body: ").append(bodyBytes).append(" bytes]\n");
                if (body.length() > MAX_BODY_PREVIEW) {
                    sb.append(body, 0, MAX_BODY_PREVIEW).append("\n... (truncated)");
                } else {
                    sb.append(body);
                }
            } else {
                sb.append("\n[Body: 0 bytes]");
            }
        } else {
            sb.append(masked);
        }
        sb.append("\n=== End Debug ===\n");
        return sb.toString();
    }

    private static String maskSecrets(String raw) {
        String result = raw;
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + maskToken("$2"));
        result = CLIENT_SECRET_BODY_PATTERN.matcher(result).replaceAll("$1" + maskToken("$2"));
        result = PASSWORD_BODY_PATTERN.matcher(result).replaceAll("$1" + maskToken("$2"));
        result = BASIC_AUTH_PATTERN.matcher(result).replaceAll("$1" + maskToken("$2"));
        return result;
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 6) {
            return "***";
        }
        return token.substring(0, 6) + "***";
    }

    private static int findEmptyLine(String text) {
        int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) return crlf;
        int lf = text.indexOf("\n\n");
        if (lf >= 0) return lf;
        return -1;
    }
}
