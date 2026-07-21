package burp.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RequestEditorMetadataSummary {
    private static final int MAX_KEYS = 12;
    private static final int MAX_KEY_LENGTH = 48;

    private RequestEditorMetadataSummary() {
    }

    static String summarizeCanonicalJson(String canonicalJson) {
        return summarize(parseMetadata(canonicalJson));
    }

    static String summarize(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "No retained source metadata.";
        }

        List<String> keys = new ArrayList<>();
        for (String key : metadata.keySet()) {
            String safe = sanitizeKey(key);
            if (!safe.isEmpty()) {
                keys.add(safe);
            }
        }
        Collections.sort(keys);

        if (keys.isEmpty()) {
            return "Retained source metadata is present. Values hidden.";
        }

        int total = keys.size();
        List<String> visible = keys.subList(0, Math.min(total, MAX_KEYS));
        StringBuilder output = new StringBuilder();
        output.append("Retained metadata keys: ")
                .append(String.join(", ", visible));

        if (total > MAX_KEYS) {
            output.append(" … +")
                    .append(total - MAX_KEYS)
                    .append(" more");
        }

        output.append(". Values hidden.");
        return output.toString();
    }

    static String sanitizeKey(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder safe = new StringBuilder();
        boolean pendingSpace = false;

        for (int index = 0;
             index < value.length() && safe.length() < MAX_KEY_LENGTH;
             index++) {
            char ch = value.charAt(index);
            boolean unsafe = ch == '\r'
                    || ch == '\n'
                    || ch < 0x20
                    || ch == 0x7f
                    || Character.isWhitespace(ch);

            if (unsafe) {
                pendingSpace = safe.length() > 0;
                continue;
            }

            if (pendingSpace && safe.length() < MAX_KEY_LENGTH) {
                safe.append(' ');
                pendingSpace = false;
            }
            safe.append(ch);
        }

        String result = safe.toString().trim();
        if (value.length() > MAX_KEY_LENGTH
                && result.length() >= MAX_KEY_LENGTH) {
            result = result.substring(0, MAX_KEY_LENGTH - 3) + "...";
        }
        return result;
    }

    private static Map<String, String> parseMetadata(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            JsonElement parsed = JsonParser.parseString(canonicalJson);
            if (parsed == null || !parsed.isJsonObject()) {
                return Collections.emptyMap();
            }
            Map<String, String> keysOnly = new LinkedHashMap<>();
            JsonObject object = parsed.getAsJsonObject();
            for (String key : object.keySet()) {
                keysOnly.put(key, "");
            }
            return keysOnly;
        } catch (RuntimeException ignored) {
            return Collections.emptyMap();
        }
    }
}
