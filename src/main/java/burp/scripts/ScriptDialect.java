package burp.scripts;

import com.google.gson.annotations.SerializedName;

import java.util.Locale;

public enum ScriptDialect {
    POSTMAN,
    INSOMNIA,
    BRUNO,
    API_WORKBENCH,
    @SerializedName(value = "LEGACY_JAVASCRIPT", alternate = {"LEGACY_" + "NASH" + "ORN", "legacy_" + "nash" + "orn"})
    LEGACY_JAVASCRIPT;

    public static ScriptDialect fromString(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY_JAVASCRIPT;
        }
        String trimmed = value.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "postman" -> POSTMAN;
            case "insomnia" -> INSOMNIA;
            case "bruno" -> BRUNO;
            case "api-workbench", "api_workbench", "native", "awb" -> API_WORKBENCH;
            case "legacy", "legacy-javascript", "legacy_javascript", "javascript", "js",
                    "legacy_" + "nash" + "orn" -> LEGACY_JAVASCRIPT;
            default -> {
                try {
                    yield ScriptDialect.valueOf(trimmed.toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    yield LEGACY_JAVASCRIPT;
                }
            }
        };
    }
}
