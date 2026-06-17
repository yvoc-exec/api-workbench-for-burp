package burp.scripts;

import java.util.Locale;

public enum ScriptDialect {
    POSTMAN,
    INSOMNIA,
    BRUNO,
    API_WORKBENCH,
    LEGACY_NASHORN;

    public static ScriptDialect fromString(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY_NASHORN;
        }
        try {
            return ScriptDialect.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "postman" -> POSTMAN;
                case "insomnia" -> INSOMNIA;
                case "bruno" -> BRUNO;
                case "api-workbench", "api_workbench", "native", "awb" -> API_WORKBENCH;
                default -> LEGACY_NASHORN;
            };
        }
    }
}
