package burp.scripts;

import java.util.Locale;

public enum ScriptScope {
    COLLECTION,
    FOLDER,
    REQUEST;

    public static ScriptScope fromString(String value) {
        if (value == null || value.isBlank()) {
            return REQUEST;
        }
        try {
            return ScriptScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "collection" -> COLLECTION;
                case "folder" -> FOLDER;
                default -> REQUEST;
            };
        }
    }
}
