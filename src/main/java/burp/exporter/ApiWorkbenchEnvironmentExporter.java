package burp.exporter;

import burp.models.EnvironmentProfile;
import com.google.gson.JsonObject;

import java.util.List;

public final class ApiWorkbenchEnvironmentExporter {
    private ApiWorkbenchEnvironmentExporter() {
    }

    public static JsonObject build(EnvironmentProfile profile, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "api-workbench-environment");
        root.addProperty("schemaVersion", 1);
        root.addProperty("id", profile != null && profile.id != null ? profile.id : "");
        root.addProperty("name", profile != null && profile.name != null ? profile.name : "");
        root.addProperty("sourceFormat", profile != null && profile.sourceFormat != null ? profile.sourceFormat : "");
        root.addProperty("sourceFileName", profile != null && profile.sourceFileName != null ? profile.sourceFileName : "");

        JsonObject vars = new JsonObject();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                vars.addProperty(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        root.add("variables", vars);

        JsonObject oauth2 = new JsonObject();
        JsonObject config = new JsonObject();
        JsonObject bindings = new JsonObject();
        if (profile != null && profile.oauth2 != null) {
            if (profile.oauth2.config != null) {
                for (var entry : profile.oauth2.config.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    config.addProperty(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }
            if (profile.oauth2.outputBindings != null) {
                for (var entry : profile.oauth2.outputBindings.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    bindings.addProperty(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }
        }
        oauth2.add("config", config);
        oauth2.add("outputBindings", bindings);
        root.add("oauth2", oauth2);
        return root;
    }
}
