package burp.exporter;

import burp.models.EnvironmentProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class InsomniaEnvironmentExporter {
    private InsomniaEnvironmentExporter() {
    }

    public static JsonObject build(EnvironmentProfile profile, List<String> warnings) {
        JsonObject root = new JsonObject();
        JsonArray resources = new JsonArray();
        JsonObject env = new JsonObject();
        env.addProperty("_id", ExportIds.environmentId(profile));
        env.addProperty("_type", "environment");
        env.addProperty("name", profile != null && profile.name != null ? profile.name : "Environment");

        JsonObject data = new JsonObject();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                data.addProperty(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        env.add("data", data);
        resources.add(env);
        root.add("resources", resources);
        return root;
    }
}
