package burp.exporter;

import burp.models.EnvironmentProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class PostmanEnvironmentExporter {
    private PostmanEnvironmentExporter() {
    }

    public static JsonObject build(EnvironmentProfile profile, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("name", profile != null && profile.name != null ? profile.name : "Environment");
        root.addProperty("_postman_variable_scope", "environment");

        JsonArray values = new JsonArray();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                JsonObject value = new JsonObject();
                value.addProperty("key", entry.getKey());
                value.addProperty("value", entry.getValue() != null ? entry.getValue() : "");
                value.addProperty("type", "default");
                value.addProperty("enabled", true);
                values.add(value);
            }
        }
        root.add("values", values);
        return root;
    }
}
