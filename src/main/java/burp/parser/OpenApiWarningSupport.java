package burp.parser;

import java.util.List;

/** Sanitized deterministic warnings for untrusted API definitions. */
final class OpenApiWarningSupport {
    private OpenApiWarningSupport() {}

    static void add(List<String> warnings, String warning) {
        if (warnings == null) return;
        String safe = label(warning);
        if (!safe.isBlank() && !warnings.contains(safe)) warnings.add(safe);
    }

    static String label(String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(Math.min(160, value.length()));
        for (int offset = 0; offset < value.length() && safe.length() < 160;) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\r' || cp == '\n' || cp == 0 || Character.isISOControl(cp)) {
                safe.append(' ');
            } else {
                safe.appendCodePoint(cp);
            }
        }
        return safe.toString().replaceAll("\\s+", " ").trim();
    }
}
