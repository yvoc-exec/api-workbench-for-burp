package burp.utils;

import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

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
        JsonElement raw = JsonParser.parseString(json);
        WorkspaceState state = GSON.fromJson(raw, WorkspaceState.class);
        state = WorkspaceStateMigrator.migrate(state);
        return normalize(state, raw);
    }

    static WorkspaceState normalize(WorkspaceState state) {
        return normalize(state, null);
    }

    static WorkspaceState normalize(WorkspaceState state, JsonElement raw) {
        WorkspaceState out = state != null ? state : new WorkspaceState();
        if (out.collections == null) {
            out.collections = new java.util.ArrayList<>();
        }
        if (out.checkedRequestKeys == null) {
            out.checkedRequestKeys = new java.util.ArrayList<>();
        }
        if (out.checkedRequestIdentityKeys == null) {
            out.checkedRequestIdentityKeys = new java.util.ArrayList<>();
        }
        if (out.expandedTreePathKeys == null) {
            out.expandedTreePathKeys = new java.util.ArrayList<>();
        }
        if (out.requestTreePaths == null) {
            out.requestTreePaths = new java.util.LinkedHashMap<>();
        }
        if (out.environments == null) {
            out.environments = new java.util.ArrayList<>();
        }
        normalizeEnvironmentProfiles(out);
        JsonObject rawRoot = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : null;
        JsonElement rawCollections = rawRoot != null ? rawRoot.get("collections") : null;
        for (int i = 0; i < out.collections.size(); i++) {
            burp.models.ApiCollection collection = out.collections.get(i);
            if (collection == null) {
                continue;
            }
            if (collection.folderAuthModes == null) {
                collection.folderAuthModes = new java.util.LinkedHashMap<>();
            }
            if (collection.folderAuth == null) {
                collection.folderAuth = new java.util.LinkedHashMap<>();
            }
            if (collection.folderVars == null) {
                collection.folderVars = new java.util.LinkedHashMap<>();
            }
            if (collection.runtimeVars == null) {
                collection.runtimeVars = new java.util.LinkedHashMap<>();
            }
            if (collection.runtimeOAuth2 == null) {
                collection.runtimeOAuth2 = new java.util.LinkedHashMap<>();
            }

            JsonObject rawCollection = getArrayObject(rawCollections, i);
            JsonElement rawRequests = rawCollection != null ? rawCollection.get("requests") : null;
            if (collection.requests != null) {
                for (int j = 0; j < collection.requests.size(); j++) {
                    burp.models.ApiRequest request = collection.requests.get(j);
                    if (request == null) {
                        continue;
                    }
                    JsonObject rawRequest = getArrayObject(rawRequests, j);
                    normalizeRequest(request, rawRequest);
                }
            }
        }
        if (out.version <= 0) {
            out.version = 1;
        }
        return out;
    }

    private static void normalizeEnvironmentProfiles(WorkspaceState state) {
        if (state == null || state.environments == null) {
            return;
        }
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (EnvironmentProfile profile : state.environments) {
            if (profile == null) {
                continue;
            }
            if (profile.variables == null) {
                profile.variables = new java.util.LinkedHashMap<>();
            }
            if (profile.oauth2 == null) {
                profile.oauth2 = new burp.models.OAuth2EnvironmentState();
            }
            profile.oauth2.ensureDefaults();
            profile.ensureId();
            while (profile.id != null && seenIds.contains(profile.id)) {
                profile.id = java.util.UUID.randomUUID().toString();
            }
            if (profile.id != null) {
                seenIds.add(profile.id);
            }
        }
        if (state.activeEnvironmentId != null && seenIds.stream().noneMatch(id -> id.equals(state.activeEnvironmentId))) {
            state.activeEnvironmentId = null;
        }
    }

    private static void normalizeRequest(burp.models.ApiRequest request, JsonObject rawRequest) {
        if (request == null) {
            return;
        }
        boolean buildModeDeclared = rawRequest != null
                && rawRequest.has("buildMode")
                && !rawRequest.get("buildMode").isJsonNull();
        if (!buildModeDeclared) {
            request.buildMode = request.editorMaterialized
                    ? burp.models.ApiRequest.BuildMode.MANUAL_PRESERVE
                    : burp.models.ApiRequest.BuildMode.AUTO_COMPATIBLE;
        }
        if (request.suppressedAutoHeaders == null) {
            request.suppressedAutoHeaders = new java.util.LinkedHashSet<>();
        }
        request.normalizeSuppressedAutoHeaders();
        removeSuppressedAutoHeadersFromRequest(request);
    }

    private static void removeSuppressedAutoHeadersFromRequest(ApiRequest request) {
        if (request == null || request.headers == null || request.suppressedAutoHeaders == null || request.suppressedAutoHeaders.isEmpty()) {
            return;
        }
        request.headers.removeIf(header -> {
            if (header == null || header.key == null) {
                return false;
            }
            String normalized = header.key.trim().toLowerCase(Locale.ROOT);
            return isTrackedAutoHeader(normalized) && request.suppressedAutoHeaders.contains(normalized);
        });
    }

    private static boolean isTrackedAutoHeader(String headerName) {
        return "authorization".equals(headerName)
                || "content-type".equals(headerName)
                || "accept".equals(headerName)
                || "user-agent".equals(headerName)
                || "cache-control".equals(headerName);
    }

    private static JsonObject getArrayObject(JsonElement arrayElement, int index) {
        if (arrayElement == null || !arrayElement.isJsonArray()) {
            return null;
        }
        if (index < 0 || index >= arrayElement.getAsJsonArray().size()) {
            return null;
        }
        JsonElement child = arrayElement.getAsJsonArray().get(index);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }
}
