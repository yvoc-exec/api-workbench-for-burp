package burp.parser;

import burp.models.ApiCollection;
import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Session-scoped provenance for Insomnia structured environment values in the string model. */
public final class InsomniaEnvironmentValueTypes {
    private static final Map<ApiCollection, Map<String, RememberedValue>> VALUES = new WeakHashMap<>();

    private InsomniaEnvironmentValueTypes() { }

    public static synchronized void remember(ApiCollection collection, String scope, String key, JsonElement value) {
        if (collection == null || key == null || value == null
                || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())) return;
        VALUES.computeIfAbsent(collection, ignored -> new HashMap<>())
                .put(provenanceKey(scope, key), new RememberedValue(
                        value.deepCopy(), normalizedRepresentation(value)));
    }

    public static synchronized JsonElement recalled(ApiCollection collection, String scope, String key, String text) {
        return recalledSource(collection, scope, key, text);
    }

    /** Returns the imported type only while the editable representation is unchanged. */
    public static synchronized JsonElement recalledSource(
            ApiCollection collection, String scope, String key, String currentText) {
        RememberedValue remembered = remembered(collection, scope, key);
        return remembered != null && Objects.equals(remembered.normalizedText, currentText)
                ? remembered.value.deepCopy() : null;
    }

    public static synchronized boolean hasRememberedSource(
            ApiCollection collection, String scope, String key) {
        return remembered(collection, scope, key) != null;
    }

    private static RememberedValue remembered(ApiCollection collection, String scope, String key) {
        Map<String, RememberedValue> values = VALUES.get(collection);
        if (values == null) return null;
        return values.get(provenanceKey(scope, key));
    }

    private static String provenanceKey(String scope, String key) {
        return (scope != null ? scope : "") + "\u0000" + key;
    }

    private static String normalizedRepresentation(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.toString();
    }

    private record RememberedValue(JsonElement value, String normalizedText) { }
}
