package burp.scripts;

import java.util.Locale;

public enum ScriptPhase {
    PRE_REQUEST,
    POST_RESPONSE,
    TEST,
    EVENT;

    public static ScriptPhase fromString(String value) {
        if (value == null || value.isBlank()) {
            return PRE_REQUEST;
        }
        try {
            return ScriptPhase.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "prerequest", "pre-request", "pre_request" -> PRE_REQUEST;
                case "postresponse", "post-response", "after-response", "after_response" -> POST_RESPONSE;
                case "test", "tests" -> TEST;
                default -> EVENT;
            };
        }
    }
}
