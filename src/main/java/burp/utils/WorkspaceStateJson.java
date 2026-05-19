package burp.utils;

import burp.models.WorkspaceState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class WorkspaceStateJson {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private WorkspaceStateJson() {}

    public static String toJson(WorkspaceState state) {
        return GSON.toJson(state != null ? state : new WorkspaceState());
    }

    public static WorkspaceState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new WorkspaceState();
        }
        WorkspaceState state = GSON.fromJson(json, WorkspaceState.class);
        return normalize(state);
    }

    static WorkspaceState normalize(WorkspaceState state) {
        WorkspaceState out = state != null ? state : new WorkspaceState();
        if (out.collections == null) {
            out.collections = new java.util.ArrayList<>();
        }
        if (out.checkedRequestKeys == null) {
            out.checkedRequestKeys = new java.util.ArrayList<>();
        }
        if (out.expandedTreePathKeys == null) {
            out.expandedTreePathKeys = new java.util.ArrayList<>();
        }
        if (out.requestTreePaths == null) {
            out.requestTreePaths = new java.util.LinkedHashMap<>();
        }
        if (out.version <= 0) {
            out.version = 1;
        }
        return out;
    }
}
