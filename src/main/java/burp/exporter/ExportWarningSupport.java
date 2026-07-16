package burp.exporter;

import java.util.List;

/** Shared, single-line warning handling for target-format exporters. */
final class ExportWarningSupport {
    private static final int MAX_LABEL_LENGTH = 160;

    private ExportWarningSupport() {
    }

    static String label(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_LABEL_LENGTH));
        boolean whitespace = false;
        for (int index = 0; index < value.length() && safe.length() < MAX_LABEL_LENGTH; index++) {
            char ch = value.charAt(index);
            boolean invalidSurrogate = Character.isSurrogate(ch)
                    && (Character.isHighSurrogate(ch)
                    ? index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))
                    : index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
            boolean separator = ch == '\r' || ch == '\n' || ch == '\u0085'
                    || ch == '\u2028' || ch == '\u2029';
            boolean control = ch < 0x20 || ch == 0x7f;
            if (separator || control || invalidSurrogate || Character.isWhitespace(ch)) {
                whitespace = safe.length() > 0;
                continue;
            }
            if (whitespace) {
                safe.append(' ');
                whitespace = false;
            }
            if (Character.isHighSurrogate(ch) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                safe.append(ch).append(value.charAt(++index));
            } else {
                safe.append(ch);
            }
        }
        String result = safe.toString().trim();
        if (value.length() > MAX_LABEL_LENGTH && safe.length() >= MAX_LABEL_LENGTH) {
            result = result.substring(0, Math.max(0, MAX_LABEL_LENGTH - 3)) + "...";
        }
        return result;
    }

    static void add(List<String> warnings, String warning) {
        if (warnings == null || warning == null) {
            return;
        }
        String safe = label(warning);
        if (!safe.isBlank() && !warnings.contains(safe)) {
            warnings.add(safe);
        }
    }
}
