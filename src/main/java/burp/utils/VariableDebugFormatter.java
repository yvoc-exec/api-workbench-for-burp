package burp.utils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Formats a variable snapshot for debug logging, masking sensitive values.
 * Supports optional source-layer annotations to show which precedence level
 * provided each resolved key.
 */
public class VariableDebugFormatter {
    private static final int MAX_KEYS = 200;
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        "token|secret|password|auth|credential|key|private|passwd|pwd|apikey",
        Pattern.CASE_INSENSITIVE
    );

    public static String format(Map<String, String> vars, String context) {
        return format(vars, null, context);
    }

    public static String format(Map<String, String> vars, Map<String, String> keySources, String context) {
        if (vars == null || vars.isEmpty()) {
            return "=== Vars [" + context + "] === (empty)\n=== End Vars ===\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Vars [").append(context).append("] ===\n");
        int count = 0;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (count >= MAX_KEYS) {
                sb.append("... (").append(vars.size() - MAX_KEYS).append(" more keys)\n");
                break;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            String display = maskIfSensitive(key, value);
            sb.append(key).append("=").append(display);
            if (keySources != null && keySources.containsKey(key)) {
                sb.append("  [").append(keySources.get(key)).append("]");
            }
            sb.append("\n");
            count++;
        }
        sb.append("=== End Vars ===\n");
        return sb.toString();
    }

    private static String maskIfSensitive(String key, String value) {
        if (value == null) return "null";
        if (SENSITIVE_KEY_PATTERN.matcher(key).find()) {
            if (value.length() <= 6) return "***";
            return value.substring(0, Math.min(6, value.length())) + "***";
        }
        // Also truncate very long values
        if (value.length() > 200) {
            return value.substring(0, 200) + "... (" + value.length() + " chars)";
        }
        return value;
    }
}
