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

        EmptyLineResult emptyLine = findEmptyLine(masked);
        if (emptyLine.index > 0) {
            String headers = masked.substring(0, emptyLine.index);
            sb.append(headers).append("\n");

            int bodyStart = emptyLine.index + emptyLine.delimiterLength;
            if (bodyStart < masked.length()) {
                String body = masked.substring(bodyStart);
                int bodyBytes = raw.length() - bodyStart;
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
        String result = applyMask(BEARER_PATTERN, raw);
        result = applyMask(CLIENT_SECRET_BODY_PATTERN, result);
        result = applyMask(PASSWORD_BODY_PATTERN, result);
        result = applyMask(BASIC_AUTH_PATTERN, result);
        return result;
    }

    private static String applyMask(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String masked = maskToken(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + masked));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 6) {
            return "***";
        }
        return token.substring(0, 6) + "***";
    }

    private static EmptyLineResult findEmptyLine(String text) {
        int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) return new EmptyLineResult(crlf, 4);
        int lf = text.indexOf("\n\n");
        if (lf >= 0) return new EmptyLineResult(lf, 2);
        return new EmptyLineResult(-1, 0);
    }

    private static class EmptyLineResult {
        final int index;
        final int delimiterLength;
        EmptyLineResult(int index, int delimiterLength) {
            this.index = index;
            this.delimiterLength = delimiterLength;
        }
    }
}
