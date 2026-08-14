package burp.utils;

import burp.history.HistoryEntry;
import burp.history.HistoryAdmissionResult;
import burp.history.HistoryJsonSupport;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryStore;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectHop;
import burp.models.WorkspaceState;
import burp.runner.RunnerRetryPolicy;
import burp.utils.ExecutionPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkspaceStateJson {
    private static final Gson GSON = gsonBuilder(false).create();
    private static final Gson CONSUMING_GSON = gsonBuilder(false)
            .registerTypeAdapterFactory(new ReleasingHistoryEntryTypeAdapterFactory())
            .create();
    private static final Gson PRETTY_GSON = gsonBuilder(true).create();

    private static GsonBuilder gsonBuilder(boolean pretty) {
        return gsonBuilder(pretty, Base64ByteArrayTypeAdapter.DEFAULT_MAX_DECODED_BYTES);
    }

    private static GsonBuilder gsonBuilder(boolean pretty, int maxDecodedBytes) {
        GsonBuilder builder = HistoryJsonSupport.configure(new GsonBuilder())
            .disableHtmlEscaping()
            .registerTypeAdapter(
                    byte[].class,
                    new Base64ByteArrayTypeAdapter(maxDecodedBytes));
        return pretty ? builder.setPrettyPrinting() : builder;
    }

    private WorkspaceStateJson() {}

    /** Normalizes and serializes a caller-owned detached workspace. */
    public static String toJson(WorkspaceState detached) {
        normalizeForSave(detached);
        return serializeDetached(detached);
    }

    /** Serializes arbitrary caller-owned state without mutating the source. */
    public static String toJsonCopying(WorkspaceState source) {
        return toJson(WorkspaceState.copyOf(source));
    }

    public static WorkspaceState normalizeForSave(WorkspaceState detached) {
        WorkspaceState owned = detached != null ? detached : new WorkspaceState();
        owned = WorkspaceStateMigrator.migrate(owned);
        normalize(owned, null, HistoryNormalizationMode.SAVE_CURRENT);
        applyRunnerRetryPolicyForSave(owned);
        canonicalizeHistoryEvidence(owned);
        validatePersistedByteArrays(owned);
        normalizeMapOrdering(owned);
        return owned;
    }

    public static String serializeDetached(WorkspaceState detached) {
        return serializeDetachedWithMetadata(
                detached, WorkspaceStateService.DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES).json();
    }

    public static SerializedWorkspace serializeDetachedWithMetadata(
            WorkspaceState detached,
            long maxSerializedBytes) {
        return serializeDetachedWithMetadata(detached, maxSerializedBytes, false);
    }

    /** Serializes a detached workspace and releases its payload before final JSON materialization. */
    public static SerializedWorkspace serializeDetachedWithMetadataAndRelease(
            WorkspaceState detached,
            long maxSerializedBytes) {
        return serializeDetachedWithMetadata(detached, maxSerializedBytes, true);
    }

    private static SerializedWorkspace serializeDetachedWithMetadata(
            WorkspaceState detached,
            long maxSerializedBytes,
            boolean releasePayload) {
        WorkspaceState value = detached != null ? detached : new WorkspaceState();
        BoundedUtf8Appendable output = new BoundedUtf8Appendable(maxSerializedBytes);
        (releasePayload ? CONSUMING_GSON : GSON).toJson(value, output);
        if (releasePayload) {
            releaseDetachedPayload(value);
        }
        return output.finish();
    }

    private static void releaseDetachedPayload(WorkspaceState detached) {
        detached.collections = new java.util.ArrayList<>();
        detached.environments = new java.util.ArrayList<>();
        detached.checkedRequestKeys = new java.util.ArrayList<>();
        detached.checkedRequestIdentityKeys = new java.util.ArrayList<>();
        detached.expandedTreePathKeys = new java.util.ArrayList<>();
        detached.requestTreePaths = new java.util.LinkedHashMap<>();
        detached.historyEntries = new java.util.ArrayList<>();
        detached.runnerQueuedRequestIdentityKeys = new java.util.ArrayList<>();
        detached.runnerRetryableMethods = new java.util.ArrayList<>();
        detached.runnerRetryableStatusCodes = new java.util.ArrayList<>();
    }

    private static void releaseHistoryEntryPayload(HistoryEntry entry) {
        if (entry == null) {
            return;
        }
        entry.requestSnapshot = null;
        entry.responseSnapshot = null;
        entry.unresolvedVariables = new java.util.ArrayList<>();
        entry.policyOverridesApplied = new java.util.ArrayList<>();
        entry.assertions = new java.util.ArrayList<>();
        entry.extractions = new java.util.ArrayList<>();
        entry.scriptLogs = new java.util.ArrayList<>();
        entry.scriptWarnings = new java.util.ArrayList<>();
        entry.scriptErrors = new java.util.ArrayList<>();
        entry.scriptVariableMutations = new java.util.ArrayList<>();
        entry.redirectHops = new java.util.ArrayList<>();
        entry.tags = new java.util.LinkedHashSet<>();
    }

    private static final class ReleasingHistoryEntryTypeAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != HistoryEntry.class) {
                return null;
            }
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            return new TypeAdapter<>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    try {
                        delegate.write(out, value);
                    } finally {
                        releaseHistoryEntryPayload((HistoryEntry) value);
                    }
                }

                @Override
                public T read(JsonReader in) throws IOException {
                    return delegate.read(in);
                }
            };
        }
    }

    static String toPrettyJsonForTests(WorkspaceState state) {
        WorkspaceState detached = WorkspaceState.copyOf(state);
        normalizeForSave(detached);
        return PRETTY_GSON.toJson(detached);
    }

    public static WorkspaceState fromJson(String json) {
        return fromJson(json, Base64ByteArrayTypeAdapter.DEFAULT_MAX_DECODED_BYTES);
    }

    static WorkspaceState fromJson(String json, int maxDecodedBytes) {
        if (json == null || json.isBlank()) {
            return normalize(new WorkspaceState(), null, HistoryNormalizationMode.LOAD_CURRENT);
        }
        preflightPersistedByteValues(json, maxDecodedBytes);
        JsonElement raw = JsonParser.parseString(json);
        Gson gson = maxDecodedBytes == Base64ByteArrayTypeAdapter.DEFAULT_MAX_DECODED_BYTES
                ? GSON
                : gsonBuilder(false, maxDecodedBytes).create();
        WorkspaceState state = gson.fromJson(raw, WorkspaceState.class);
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
        if (out.redirectPolicy == null) {
            out.redirectPolicy = burp.models.RedirectPolicy.defaults();
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
        if (mode == HistoryNormalizationMode.SAVE_CURRENT) {
            List<HistoryEntry> incoming = state.historyEntries != null
                    ? state.historyEntries
                    : new ArrayList<>();
            validateCurrentHistory(incoming, state.historyRetentionPolicy);
            state.historyEntries = incoming;
            state.historyLegacyCompactedEntryCount = 0;
            if (state.historyRetentionPolicyVersion == null
                    || state.historyRetentionPolicyVersion < HistoryRetentionPolicy.CURRENT_POLICY_VERSION) {
                state.historyRetentionPolicyVersion = HistoryRetentionPolicy.CURRENT_POLICY_VERSION;
            }
            return;
        }
        List<HistoryEntry> incoming = copyHistory(state.historyEntries);
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

    private static void canonicalizeHistoryEvidence(WorkspaceState state) {
        if (state == null || state.historyEntries == null) {
            return;
        }
        for (HistoryEntry entry : state.historyEntries) {
            if (entry == null) {
                continue;
            }
            if (entry.requestSnapshot != null) {
                entry.requestSnapshot.canonicalizeExactTransportOwnership();
            }
            if (entry.redirectHops != null) {
                for (RedirectHop hop : entry.redirectHops) {
                    if (hop != null) {
                        hop.canonicalizeRawEvidence();
                    }
                }
            }
        }
    }

    private static void validatePersistedByteArrays(WorkspaceState state) {
        int limit = Base64ByteArrayTypeAdapter.DEFAULT_MAX_DECODED_BYTES;
        if (state.collections != null) {
            for (burp.models.ApiCollection collection : state.collections) {
                if (collection == null || collection.requests == null) {
                    continue;
                }
                for (ApiRequest request : collection.requests) {
                    if (request != null && request.exactHttpRequest != null) {
                        validateBytes(request.exactHttpRequest.rawRequestBytes, limit);
                    }
                }
            }
        }
        if (state.historyEntries != null) {
            for (HistoryEntry entry : state.historyEntries) {
                if (entry == null) {
                    continue;
                }
                if (entry.requestSnapshot != null) {
                    validateBytes(entry.requestSnapshot.bodyAsAuthored, limit);
                    validateBytes(entry.requestSnapshot.rawRequestSent, limit);
                }
                if (entry.responseSnapshot != null) {
                    validateBytes(entry.responseSnapshot.body, limit);
                }
                if (entry.redirectHops != null) {
                    for (RedirectHop hop : entry.redirectHops) {
                        if (hop != null) {
                            validateBytes(hop.rawRequestBytes, limit);
                            validateBytes(hop.responseBody, limit);
                        }
                    }
                }
            }
        }
    }

    private static void validateBytes(byte[] bytes, int limit) {
        if (bytes != null && bytes.length > limit) {
            throw new IllegalStateException(
                    "Persisted byte value exceeds the configured " + limit + " byte limit.");
        }
    }

    private static void normalizeMapOrdering(WorkspaceState state) {
        state.requestTreePaths = sortedMap(state.requestTreePaths);
        if (state.environments != null) {
            for (EnvironmentProfile profile : state.environments) {
                if (profile == null) {
                    continue;
                }
                profile.variables = sortedMap(profile.variables);
                profile.runtimeVariables = sortedMap(profile.runtimeVariables);
                if (profile.oauth2 != null) {
                    profile.oauth2.config = sortedMap(profile.oauth2.config);
                    profile.oauth2.outputBindings = sortedMap(profile.oauth2.outputBindings);
                }
            }
        }
        if (state.collections != null) {
            for (burp.models.ApiCollection collection : state.collections) {
                normalizeCollectionMaps(collection);
            }
        }
        if (state.historyEntries != null) {
            for (HistoryEntry entry : state.historyEntries) {
                if (entry != null && entry.requestSnapshot != null) {
                    entry.requestSnapshot.requestVariablesAsAuthored =
                            sortedMap(entry.requestSnapshot.requestVariablesAsAuthored);
                    entry.requestSnapshot.resolvedVariables =
                            sortedMap(entry.requestSnapshot.resolvedVariables);
                }
            }
        }
    }

    private static void normalizeCollectionMaps(burp.models.ApiCollection collection) {
        if (collection == null) {
            return;
        }
        collection.sourceMetadata = sortedMap(collection.sourceMetadata);
        collection.folderAuthModes = sortedMap(collection.folderAuthModes);
        collection.folderAuth = sortedMap(collection.folderAuth);
        collection.folderVars = sortedNestedMap(collection.folderVars);
        collection.runtimeFolderVars = sortedNestedMap(collection.runtimeFolderVars);
        collection.folderScriptBlocks = sortedMap(collection.folderScriptBlocks);
        collection.environment = sortedMap(collection.environment);
        collection.runtimeVars = sortedMap(collection.runtimeVars);
        collection.runtimeOAuth2 = sortedMap(collection.runtimeOAuth2);
        normalizeAuth(collection.auth);
        for (ApiRequest.Auth auth : collection.folderAuth.values()) {
            normalizeAuth(auth);
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                normalizeRequestMaps(request);
            }
        }
    }

    private static void normalizeRequestMaps(ApiRequest request) {
        if (request == null) {
            return;
        }
        request.sourceMetadata = sortedMap(request.sourceMetadata);
        normalizeAuth(request.auth);
        normalizeAuth(request.explicitAuth);
        if (request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter != null) {
                    parameter.sourceMetadata = sortedMap(parameter.sourceMetadata);
                }
            }
        }
        if (request.body != null) {
            normalizeFormFieldMaps(request.body.formdata);
            normalizeFormFieldMaps(request.body.urlencoded);
        }
    }

    private static void normalizeFormFieldMaps(List<ApiRequest.Body.FormField> fields) {
        if (fields == null) {
            return;
        }
        for (ApiRequest.Body.FormField field : fields) {
            if (field != null) {
                field.sourceMetadata = sortedMap(field.sourceMetadata);
            }
        }
    }

    private static void normalizeAuth(ApiRequest.Auth auth) {
        if (auth != null) {
            auth.properties = sortedMap(auth.properties);
        }
    }

    private static <V> LinkedHashMap<String, V> sortedMap(Map<String, V> source) {
        LinkedHashMap<String, V> sorted = new LinkedHashMap<>();
        if (source == null) {
            return sorted;
        }
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static LinkedHashMap<String, Map<String, String>> sortedNestedMap(
            Map<String, Map<String, String>> source) {
        LinkedHashMap<String, Map<String, String>> sorted = new LinkedHashMap<>();
        sortedMap(source).forEach((key, value) -> sorted.put(key, sortedMap(value)));
        return sorted;
    }

    private static void preflightPersistedByteValues(String json, int maxDecodedBytes) {
        new Base64ByteArrayTypeAdapter(maxDecodedBytes);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            scanJsonValue(reader, new ArrayList<>(), maxDecodedBytes);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("Workspace JSON contains trailing content.");
            }
        } catch (IOException e) {
            throw new JsonParseException("Workspace JSON byte preflight failed.", e);
        }
    }

    private static void scanJsonValue(
            JsonReader reader,
            List<String> path,
            int maxDecodedBytes) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                path.add(name);
                if (isPersistedBytePath(path)) {
                    scanPersistedByteValue(reader, path, maxDecodedBytes);
                } else {
                    scanJsonValue(reader, path, maxDecodedBytes);
                }
                path.remove(path.size() - 1);
            }
            reader.endObject();
            return;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) {
                scanJsonValue(reader, path, maxDecodedBytes);
            }
            reader.endArray();
            return;
        }
        reader.skipValue();
    }

    private static void scanPersistedByteValue(
            JsonReader reader,
            List<String> path,
            int maxDecodedBytes) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.STRING) {
            String encoded = reader.nextString();
            if (encoded.startsWith(Base64ByteArrayTypeAdapter.PREFIX)) {
                int payloadLength = encoded.length() - Base64ByteArrayTypeAdapter.PREFIX.length();
                long maximumEncodedLength = ((long) maxDecodedBytes + 2L) / 3L * 4L;
                if (payloadLength > maximumEncodedLength) {
                    throw byteValueTooLarge(maxDecodedBytes);
                }
            }
            return;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            int count = 0;
            while (reader.hasNext()) {
                if (count >= maxDecodedBytes) {
                    throw byteValueTooLarge(maxDecodedBytes);
                }
                reader.skipValue();
                count++;
            }
            reader.endArray();
            return;
        }
        scanJsonValue(reader, path, maxDecodedBytes);
    }

    private static boolean isPersistedBytePath(List<String> path) {
        if (path.isEmpty()) {
            return false;
        }
        String name = path.get(path.size() - 1);
        if ("rawRequestBytes".equals(name)
                || "bodyAsAuthored".equals(name)
                || "rawRequestSent".equals(name)
                || "responseBody".equals(name)) {
            return true;
        }
        int size = path.size();
        return "body".equals(name)
                && size >= 3
                && "historyEntries".equals(path.get(size - 3))
                && "responseSnapshot".equals(path.get(size - 2));
    }

    private static JsonParseException byteValueTooLarge(int maxDecodedBytes) {
        return new JsonParseException(
                "Decoded byte value exceeds the configured " + maxDecodedBytes + " byte limit.");
    }

    public static final class SerializedWorkspace {
        private final String json;
        private final long utf8Length;
        private final String sha256;

        private SerializedWorkspace(String json, long utf8Length, String sha256) {
            this.json = json;
            this.utf8Length = utf8Length;
            this.sha256 = sha256;
        }

        public String json() {
            return json;
        }

        public long utf8Length() {
            return utf8Length;
        }

        public String sha256() {
            return sha256;
        }
    }

    private static final class BoundedUtf8Appendable implements Appendable {
        private static final int CHUNK_CHARS = 1024 * 1024;

        private final long maxBytes;
        private final List<String> fullChunks = new ArrayList<>();
        private final StringBuilder currentChunk = new StringBuilder(CHUNK_CHARS);
        private final MessageDigest digest;
        private long utf8Length;
        private char pendingHighSurrogate;

        private BoundedUtf8Appendable(long maxBytes) {
            if (maxBytes <= 0L) {
                throw new IllegalArgumentException("Serialized workspace byte limit must be positive.");
            }
            this.maxBytes = maxBytes;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable.", e);
            }
        }

        @Override
        public Appendable append(CharSequence chars) {
            CharSequence actual = chars != null ? chars : "null";
            return append(actual, 0, actual.length());
        }

        @Override
        public Appendable append(CharSequence chars, int start, int end) {
            if (chars == null) {
                chars = "null";
            }
            if (start < 0 || end < start || end > chars.length()) {
                throw new IndexOutOfBoundsException();
            }
            for (int index = start; index < end; index++) {
                append(chars.charAt(index));
            }
            return this;
        }

        @Override
        public Appendable append(char character) {
            updateUtf8(character);
            currentChunk.append(character);
            if (currentChunk.length() == CHUNK_CHARS) {
                fullChunks.add(currentChunk.toString());
                currentChunk.setLength(0);
            }
            return this;
        }

        private void updateUtf8(char character) {
            if (pendingHighSurrogate != 0) {
                if (!Character.isLowSurrogate(character)) {
                    throw new IllegalArgumentException("Workspace text contains invalid UTF-16 data.");
                }
                int codePoint = Character.toCodePoint(pendingHighSurrogate, character);
                pendingHighSurrogate = 0;
                writeByte(0xf0 | (codePoint >> 18));
                writeByte(0x80 | ((codePoint >> 12) & 0x3f));
                writeByte(0x80 | ((codePoint >> 6) & 0x3f));
                writeByte(0x80 | (codePoint & 0x3f));
                return;
            }
            if (Character.isHighSurrogate(character)) {
                pendingHighSurrogate = character;
                return;
            }
            if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Workspace text contains invalid UTF-16 data.");
            }
            if (character <= 0x7f) {
                writeByte(character);
            } else if (character <= 0x7ff) {
                writeByte(0xc0 | (character >> 6));
                writeByte(0x80 | (character & 0x3f));
            } else {
                writeByte(0xe0 | (character >> 12));
                writeByte(0x80 | ((character >> 6) & 0x3f));
                writeByte(0x80 | (character & 0x3f));
            }
        }

        private void writeByte(int byteValue) {
            if (utf8Length >= maxBytes) {
                throw new IllegalStateException(
                        "Serialized workspace exceeds the configured " + maxBytes + " byte limit.");
            }
            digest.update((byte) byteValue);
            utf8Length++;
        }

        private SerializedWorkspace finish() {
            if (pendingHighSurrogate != 0) {
                throw new IllegalArgumentException("Workspace text contains invalid UTF-16 data.");
            }
            if (!currentChunk.isEmpty()) {
                fullChunks.add(currentChunk.toString());
            }
            String json = String.join("", fullChunks);
            fullChunks.clear();
            return new SerializedWorkspace(json, utf8Length, hex(digest.digest()));
        }

        private static String hex(byte[] bytes) {
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                out.append(Character.forDigit((value >>> 4) & 0xf, 16));
                out.append(Character.forDigit(value & 0xf, 16));
            }
            return out.toString();
        }
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
