package burp.parser;

import burp.models.ApiCollection;
import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Session-scoped provenance for Insomnia structured environment values in the string model. */
public final class InsomniaEnvironmentValueTypes {
    private static final Map<ApiCollection, Map<String, JsonElement>> VALUES = new WeakHashMap<>();

    private InsomniaEnvironmentValueTypes() { }

    public static synchronized void remember(ApiCollection collection, String scope, String key, JsonElement value) {
        if (collection == null || key == null || value == null
                || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())) return;
        VALUES.computeIfAbsent(collection, ignored -> new HashMap<>())
                .put((scope != null ? scope : "") + "\u0000" + key, value.deepCopy());
    }

    public static synchronized JsonElement recalled(ApiCollection collection, String scope, String key, String text) {
        JsonElement value = recalledSource(collection, scope, key);
        return value != null && value.toString().equals(text) ? value.deepCopy() : null;
    }

    /** Returns the imported source type even when export-time resolution changes its text. */
    public static synchronized JsonElement recalledSource(ApiCollection collection, String scope, String key) {
        Map<String, JsonElement> values = VALUES.get(collection);
        if (values == null) return null;
        JsonElement value = values.get((scope != null ? scope : "") + "\u0000" + key);
        return value != null ? value.deepCopy() : null;
    }
}
