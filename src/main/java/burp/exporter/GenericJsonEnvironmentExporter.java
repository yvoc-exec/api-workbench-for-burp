package burp.exporter;

import burp.models.EnvironmentProfile;
import com.google.gson.JsonObject;

import java.util.List;

public final class GenericJsonEnvironmentExporter {
    private GenericJsonEnvironmentExporter() {
    }

    public static JsonObject build(EnvironmentProfile profile, List<String> warnings) {
        JsonObject root = new JsonObject();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                root.addProperty(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return root;
    }
}
