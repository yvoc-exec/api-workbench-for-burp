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
        root.addProperty("__type", "export");
        root.addProperty("__export_format", 4);
        root.addProperty("__export_source", "api-workbench-for-burp");
        JsonArray resources = new JsonArray();
        ExportIds.Allocator ids = new ExportIds.Allocator();
        String workspaceId = ids.allocate("wrk_" + ExportIds.slug(profile != null ? profile.displayName() : "environment"), "wrk_environment");
        JsonObject workspace = new JsonObject();
        workspace.addProperty("_id", workspaceId);
        workspace.addProperty("_type", "workspace");
        workspace.addProperty("name", profile != null ? profile.displayName() : "Environment");
        resources.add(workspace);

        JsonObject env = new JsonObject();
        env.addProperty("_id", ids.allocate(ExportIds.environmentId(profile), "env_environment"));
        env.addProperty("_type", "environment");
        env.addProperty("parentId", workspaceId);
        env.addProperty("name", profile != null ? profile.displayName() : "Environment");
        JsonObject data = new JsonObject();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    ExportWarningSupport.add(warnings, "Insomnia environment export skipped a blank variable key.");
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
