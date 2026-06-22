package burp.diagnostics;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DiagnosticSanitizer {
    private static final Pattern HEADER_LINE = Pattern.compile("(?im)^(authorization|cookie|set-cookie)\\s*:\\s*.*$");
    private static final Pattern JSON_KEY_VALUE = Pattern.compile("(?i)(\"(?:access_token|refresh_token|client_secret|password|api_key|apikey|token|secret)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(access_token|refresh_token|client_secret|password|api_key|apikey|token|secret|bearer)\\s*[=:]\\s*([^\\s,&;]+)");

    private DiagnosticSanitizer() {
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String sanitized = HEADER_LINE.matcher(text).replaceAll(match -> maskHeader(match.group(1)));
        sanitized = JSON_KEY_VALUE.matcher(sanitized).replaceAll(match -> match.group(1) + "***" + match.group(3));
        sanitized = KEY_VALUE.matcher(sanitized).replaceAll(match -> match.group(1) + "=***");
        sanitized = sanitized.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-~+/=]+", "Bearer ***");
        sanitized = sanitized.replaceAll("(?i)basic\\s+[A-Za-z0-9._\\-~+/=]+", "Basic ***");
        return sanitized;
    }

    private static String maskHeader(String headerName) {
        return headerName.toUpperCase(Locale.ROOT) + ": ***";
    }
}
