package burp.utils;

import burp.history.HistoryEntry;
import burp.history.HistoryAdmissionResult;
import burp.history.HistoryJsonSupport;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryStore;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.runner.RunnerRetryPolicy;
import burp.utils.ExecutionPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public final class WorkspaceStateJson {
    private static final Gson GSON = HistoryJsonSupport.configure(new GsonBuilder())
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private WorkspaceStateJson() {}

    public static String toJson(WorkspaceState state) {
        WorkspaceState snapshot = WorkspaceState.copyOf(state);
        // The general migrator supplies defaults to History entries. A current
        // save must not run that mutation path over an already bounded store
        // snapshot, so detach History while the general workspace is migrated.
        List<HistoryEntry> boundedHistory = copyHistory(snapshot.historyEntries);
        snapshot.historyEntries = new ArrayList<>();
        snapshot = WorkspaceStateMigrator.migrate(snapshot);
        snapshot.historyEntries = boundedHistory;
        normalize(snapshot, null, HistoryNormalizationMode.SAVE_CURRENT);
        return GSON.toJson(snapshot);
    }

    public static WorkspaceState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return normalize(new WorkspaceState(), null, HistoryNormalizationMode.LOAD_CURRENT);
        }
        JsonElement raw = JsonParser.parseString(json);
        WorkspaceState state = GSON.fromJson(raw, WorkspaceState.class);
        state = WorkspaceStateMigrator.migrate(state);
        return normalize(state, raw, historyMode(raw));
    }

    static WorkspaceState normalize(WorkspaceState state) {
        return normalize(state, null, HistoryNormalizationMode.SAVE_CURRENT);
    }

    static WorkspaceState normalize(WorkspaceState state, JsonElement raw) {
        return normalize(state, raw, historyMode(raw));
    }

    private static WorkspaceState normalize(
            WorkspaceState state,
            JsonElement raw,
            HistoryNormalizationMode historyMode) {
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
        if (out.runnerQueuedRequestIdentityKeys == null) {
            out.runnerQueuedRequestIdentityKeys = new java.util.ArrayList<>();
        }
        normalizeRunnerRetryPolicy(out);
        out.historyRetentionPolicy = normalizedHistoryPolicy(out.historyRetentionPolicy);
        if (out.environments == null) {
            out.environments = new java.util.ArrayList<>();
        }
        normalizeHistory(out, historyMode);
        if (out.defaultResponseTimeoutMillis == null || out.defaultResponseTimeoutMillis <= 0) {
            out.defaultResponseTimeoutMillis = 30_000;
        } else if (out.defaultResponseTimeoutMillis < 1_000) {
            out.defaultResponseTimeoutMillis = 1_000;
        } else if (out.defaultResponseTimeoutMillis > 300_000) {
            out.defaultResponseTimeoutMillis = 300_000;
        }
        if (out.runnerResponseTimeoutMillis == null || out.runnerResponseTimeoutMillis <= 0) {
            out.runnerResponseTimeoutMillis = out.defaultResponseTimeoutMillis;
        } else if (out.runnerResponseTimeoutMillis < 1_000) {
            out.runnerResponseTimeoutMillis = 1_000;
        } else if (out.runnerResponseTimeoutMillis > 300_000) {
            out.runnerResponseTimeoutMillis = 300_000;
        }
        if (out.workbenchScriptFailureMode == null) {
            out.workbenchScriptFailureMode = ExecutionPolicy.ScriptFailureMode.ABORT;
        }
        if (out.oauth2FailureMode == null) {
            out.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.ABORT;
        }
        if (out.workbenchTargetChangeMode == null) {
            out.workbenchTargetChangeMode = ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION;
        }
        if (out.workbenchUnresolvedVariableMode == null) {
            out.workbenchUnresolvedVariableMode = ExecutionPolicy.UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        }
        if (out.runnerTargetChangeMode == null || out.runnerTargetChangeMode == ExecutionPolicy.TargetChangeMode.REQUIRE_CONFIRMATION) {
            out.runnerTargetChangeMode = ExecutionPolicy.TargetChangeMode.ABORT;
        }
        normalizeEnvironmentProfiles(out);
        JsonObject rawRoot = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : null;
        JsonElement rawCollections = rawRoot != null ? rawRoot.get("collections") : null;
        normalizeCollectionProfiles(out);
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
            if (collection.folderPaths == null) {
                collection.folderPaths = new java.util.ArrayList<>();
            } else {
                collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
            }
            if (collection.folderVars == null) {
                collection.folderVars = new java.util.LinkedHashMap<>();
            }
            if (collection.runtimeFolderVars == null) {
                collection.runtimeFolderVars = new java.util.LinkedHashMap<>();
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
        if (out.version < WorkspaceState.CURRENT_VERSION) {
            out.version = WorkspaceState.CURRENT_VERSION;
        }
        return out;
    }

    private static HistoryRetentionPolicy normalizedHistoryPolicy(HistoryRetentionPolicy policy) {
        HistoryRetentionPolicy normalized = policy != null
                ? HistoryRetentionPolicy.copyOf(policy)
                : HistoryRetentionPolicy.defaultPolicy();
        normalized.normalize();
        return normalized;
    }

    private static void normalizeHistory(WorkspaceState state, HistoryNormalizationMode mode) {
        List<HistoryEntry> incoming = copyHistory(state.historyEntries);
        if (mode == HistoryNormalizationMode.SAVE_CURRENT) {
            validateCurrentHistory(incoming, state.historyRetentionPolicy);
            state.historyEntries = incoming;
            state.historyLegacyCompactedEntryCount = 0;
            if (state.historyRetentionPolicyVersion == null
                    || state.historyRetentionPolicyVersion < HistoryRetentionPolicy.CURRENT_POLICY_VERSION) {
                state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
            }
            return;
        }
        HistoryStore staging = new HistoryStore();
        HistoryAdmissionResult restoreResult = staging.restoreAll(
                incoming,
                state.historyRetentionPolicy,
                mode == HistoryNormalizationMode.LOAD_LEGACY);
        if (!restoreResult.accepted()) {
            throw historyMigrationFailure();
        }
        state.historyEntries = staging.snapshot();
        state.historyLegacyCompactedEntryCount = mode == HistoryNormalizationMode.LOAD_LEGACY
                ? staging.getRetentionStats().legacyCompactedEntryCount()
                : 0;
        if (state.historyRetentionPolicyVersion == null
                || state.historyRetentionPolicyVersion < HistoryRetentionPolicy.CURRENT_POLICY_VERSION) {
            state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
        }
    }

    private static void validateCurrentHistory(
            List<HistoryEntry> entries,
            HistoryRetentionPolicy policy) {
        if (entries.size() > policy.maxEntries) {
            throw new IllegalStateException("Current History state exceeds the configured retention policy.");
        }
        long total = 0L;
        for (HistoryEntry entry : entries) {
            long size = entry != null ? Math.max(0L, entry.estimatedStoredBytes()) : 0L;
            if (Long.MAX_VALUE - total < size) {
                total = Long.MAX_VALUE;
            } else {
                total += size;
            }
        }
        if (total > policy.maxTotalStoredBytes) {
            throw new IllegalStateException("Current History state exceeds the configured retention policy.");
        }
    }

    private static HistoryNormalizationMode historyMode(JsonElement raw) {
        JsonObject root = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : null;
        if (root == null || !root.has("historyRetentionPolicyVersion")) {
            return HistoryNormalizationMode.LOAD_LEGACY;
        }
        JsonElement version = root.get("historyRetentionPolicyVersion");
        if (version == null || version.isJsonNull() || !version.isJsonPrimitive()) {
            return HistoryNormalizationMode.LOAD_LEGACY;
        }
        try {
            return version.getAsInt() < HistoryRetentionPolicy.CURRENT_POLICY_VERSION
                    ? HistoryNormalizationMode.LOAD_LEGACY
                    : HistoryNormalizationMode.LOAD_CURRENT;
        } catch (RuntimeException ignored) {
            return HistoryNormalizationMode.LOAD_LEGACY;
        }
    }

    private static List<HistoryEntry> copyHistory(java.util.Collection<HistoryEntry> entries) {
        List<HistoryEntry> copies = new ArrayList<>();
        if (entries == null) {
            return copies;
        }
        for (HistoryEntry entry : entries) {
            HistoryEntry copy = HistoryEntry.copyOf(entry);
            if (copy != null) {
                copies.add(copy);
            }
        }
        return copies;
    }

    private static IllegalStateException historyMigrationFailure() {
        return new IllegalStateException(
                "History retention migration could not satisfy the configured policy.");
    }

    private enum HistoryNormalizationMode {
        LOAD_LEGACY,
        LOAD_CURRENT,
        SAVE_CURRENT
    }

    private static void applyRunnerRetryPolicyForSave(WorkspaceState state) {
        if (state == null) {
            return;
        }
        RunnerRetryPolicy policy = runnerRetryPolicyFromState(state);
        policy.normalize();
        state.runnerRetryPolicyVersion = 1;
        state.runnerRetries = policy.maxRetries;
        state.runnerRetryableMethods = new java.util.ArrayList<>(policy.retryableMethods.stream().sorted().toList());
        state.runnerRetryableStatusCodes = new java.util.ArrayList<>(policy.retryableStatusCodes.stream().sorted().toList());
        state.runnerRetryConnectionFailures = policy.retryConnectionFailures;
        state.runnerRetryTimeouts = policy.retryTimeouts;
        state.runnerRetryNonIdempotentMethods = policy.retryNonIdempotentMethods;
        state.runnerRetryBaseDelayMillis = policy.baseDelayMillis;
        state.runnerRetryMaxDelayMillis = policy.maxDelayMillis;
    }

    private static void normalizeRunnerRetryPolicy(WorkspaceState state) {
        if (state == null) {
            return;
        }
        if (state.runnerRetryPolicyVersion != null) {
            RunnerRetryPolicy policy = runnerRetryPolicyFromState(state);
            policy.normalize();
            writeRunnerRetryPolicyToState(state, policy);
            state.runnerRetryPolicyVersion = 1;
            return;
        }
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = state.runnerRetries != null ? state.runnerRetries : 0;
        policy.retryableMethods = new java.util.LinkedHashSet<>(java.util.List.of("GET", "HEAD", "OPTIONS"));
        policy.retryableStatusCodes = new java.util.LinkedHashSet<>();
        if (policy.maxRetries > 0) {
            policy.retryConnectionFailures = true;
            policy.retryTimeouts = true;
        }
        writeRunnerRetryPolicyToState(state, policy);
    }

    private static RunnerRetryPolicy runnerRetryPolicyFromState(WorkspaceState state) {
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        if (state == null) {
            return policy;
        }
        policy.maxRetries = state.runnerRetries != null ? state.runnerRetries : policy.maxRetries;
        policy.retryableMethods = state.runnerRetryableMethods != null && !state.runnerRetryableMethods.isEmpty()
                ? new java.util.LinkedHashSet<>(state.runnerRetryableMethods)
                : new java.util.LinkedHashSet<>(policy.retryableMethods);
        policy.retryableStatusCodes = state.runnerRetryableStatusCodes != null
                ? new java.util.LinkedHashSet<>(state.runnerRetryableStatusCodes)
                : new java.util.LinkedHashSet<>();
        policy.retryConnectionFailures = state.runnerRetryConnectionFailures != null ? state.runnerRetryConnectionFailures : policy.retryConnectionFailures;
        policy.retryTimeouts = state.runnerRetryTimeouts != null ? state.runnerRetryTimeouts : policy.retryTimeouts;
        policy.retryNonIdempotentMethods = state.runnerRetryNonIdempotentMethods != null ? state.runnerRetryNonIdempotentMethods : policy.retryNonIdempotentMethods;
        policy.baseDelayMillis = state.runnerRetryBaseDelayMillis != null ? state.runnerRetryBaseDelayMillis : policy.baseDelayMillis;
        policy.maxDelayMillis = state.runnerRetryMaxDelayMillis != null ? state.runnerRetryMaxDelayMillis : policy.maxDelayMillis;
        return policy;
    }

    private static void writeRunnerRetryPolicyToState(WorkspaceState state, RunnerRetryPolicy policy) {
        if (state == null || policy == null) {
            return;
        }
        policy.normalize();
        state.runnerRetries = policy.maxRetries;
        state.runnerRetryableMethods = new java.util.ArrayList<>(policy.retryableMethods.stream().sorted().toList());
        state.runnerRetryableStatusCodes = new java.util.ArrayList<>(policy.retryableStatusCodes.stream().sorted().toList());
        state.runnerRetryConnectionFailures = policy.retryConnectionFailures;
        state.runnerRetryTimeouts = policy.retryTimeouts;
        state.runnerRetryNonIdempotentMethods = policy.retryNonIdempotentMethods;
        state.runnerRetryBaseDelayMillis = policy.baseDelayMillis;
        state.runnerRetryMaxDelayMillis = policy.maxDelayMillis;
    }

    private static void normalizeCollectionProfiles(WorkspaceState state) {
        if (state == null || state.collections == null) {
            return;
        }
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (burp.models.ApiCollection collection : state.collections) {
            if (collection == null) {
                continue;
            }
            collection.ensureId();
            while (collection.id != null && seenIds.contains(collection.id)) {
                collection.id = java.util.UUID.randomUUID().toString();
            }
            if (collection.id != null) {
                seenIds.add(collection.id);
            }
        }
    }

    private static java.util.List<String> normalizeFolderPaths(java.util.List<String> folderPaths) {
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (folderPaths == null) {
            return out;
        }
        for (String folderPath : folderPaths) {
            String value = burp.utils.AuthInheritanceResolver.normalizeFolderPath(folderPath != null ? folderPath.replace('\\', '/') : null);
            if (!value.isEmpty() && normalized.add(value)) {
                out.add(value);
            }
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
            if (profile.runtimeVariables == null) {
                profile.runtimeVariables = new java.util.LinkedHashMap<>();
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
        if (rawRequest != null) {
            boolean buildModeDeclared = rawRequest.has("buildMode")
                    && !rawRequest.get("buildMode").isJsonNull();
            if (!buildModeDeclared) {
                request.buildMode = request.editorMaterialized
                        ? burp.models.ApiRequest.BuildMode.MANUAL_PRESERVE
                        : burp.models.ApiRequest.BuildMode.AUTO_COMPATIBLE;
            }
        }
        if (request.suppressedAutoHeaders == null) {
            request.suppressedAutoHeaders = new java.util.LinkedHashSet<>();
        }
        request.normalizeSuppressedAutoHeaders();
        removeSuppressedAutoHeadersFromRequest(request);
        ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request);
        if (rawRequest != null) {
            boolean parametersDeclared =
                    rawRequest.has("parameters")
                            && !rawRequest.get("parameters").isJsonNull();
            CanonicalRequestModelMigrationSupport
                    .migrateLegacyEmbeddedQuery(
                            request,
                            parametersDeclared);
        }
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
