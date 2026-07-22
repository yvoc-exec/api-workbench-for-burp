package burp.performance;

import burp.history.HistoryAssertionResult;
import burp.history.HistoryEntry;
import burp.history.HistoryExtractionResult;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistoryResult;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.RedirectHop;
import burp.models.RunnerResult;
import burp.models.WorkspaceState;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptLogEntry;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.scripts.ScriptVariableMutation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Deterministic, publish-safe data generators for the opt-in memory baseline. */
public final class MemoryHardeningFixtureFactory {
    private static final byte[] HTTP_PREFIX = "POST /memory HTTP/1.1\r\nHost: example.test\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);

    private MemoryHardeningFixtureFactory() {
    }

    public static byte[] binaryBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        byte[] out = new byte[length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((i * 31 + 17) & 0xff);
        }
        return out;
    }

    public static String textBytes(int utf8Length) {
        if (utf8Length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        return "x".repeat(utf8Length);
    }

    public static byte[] rawHttpRequest(int exactLength) {
        if (exactLength < HTTP_PREFIX.length) {
            throw new IllegalArgumentException("raw request shorter than HTTP prefix");
        }
        byte[] out = new byte[exactLength];
        System.arraycopy(HTTP_PREFIX, 0, out, 0, HTTP_PREFIX.length);
        Arrays.fill(out, HTTP_PREFIX.length, out.length, (byte) 'r');
        return out;
    }

    public static String jsonBody(int approximateUtf8Bytes, int depth, boolean array) {
        int safeDepth = Math.max(1, Math.min(depth, 32));
        String value = "\"payload\":\"" + "j".repeat(Math.max(0, approximateUtf8Bytes - 64)) + "\"";
        String json = "{" + value + "}";
        for (int i = 1; i < safeDepth; i++) {
            json = "{\"level" + i + "\":" + json + "}";
        }
        return array ? "[" + json + "]" : json;
    }

    public static ExactHttpRequestSnapshot exactSnapshot(int rawLength) {
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        snapshot.rawRequestBytes = rawHttpRequest(rawLength);
        snapshot.serviceHost = "example.test";
        snapshot.servicePort = 443;
        snapshot.secure = true;
        snapshot.httpVersion = "HTTP/1.1";
        snapshot.pristine = true;
        snapshot.binaryBody = false;
        snapshot.sourceContext = "memory-baseline";
        return snapshot;
    }

    public static ApiRequest fidelityRequest(String id, int bodyBytes) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = "Memory request " + id;
        request.path = "Memory/Generated";
        request.sourceCollection = "Memory Baseline";
        request.method = "POST";
        request.url = "https://example.test/memory/{id}?flag&empty=";
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.sourceMetadata.put("fixture", "memory-hardening");

        ApiRequest.Parameter path = new ApiRequest.Parameter("path", "id", id);
        path.required = true;
        path.type = "string";
        path.description = "deterministic identifier";
        path.style = "simple";
        path.explode = false;
        path.source = "fixture";
        path.sourceMetadata.put("origin", "generated");
        request.parameters.add(path);
        ApiRequest.Parameter flag = new ApiRequest.Parameter("query", "flag", "retained");
        flag.valuePresent = false;
        flag.rawKey = "flag";
        request.parameters.add(flag);
        ApiRequest.Parameter empty = new ApiRequest.Parameter("query", "empty", "");
        empty.valuePresent = true;
        request.parameters.add(empty);
        request.headers.add(new ApiRequest.Header("X-Memory-Fixture", "baseline"));

        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = textBytes(bodyBytes);
        request.body.contentType = "text/plain";
        request.body.required = true;
        request.body.description = "generated logical payload";
        request.body.source = "fixture";
        request.body.sourceMetadata.put("retained", "true");

        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "fixtureValue";
        variable.value = "placeholder-value";
        variable.type = "string";
        request.variables.add(variable);
        request.scriptBlocks.add(scriptBlock("script-request-" + id, ScriptScope.REQUEST));
        return request;
    }

    public static HistoryEntry historyEntry(int index, int requestBytes, int responseBytes) {
        ApiRequest authored = fidelityRequest("history-" + index, Math.max(0, requestBytes / 4));
        HistoryEntry entry = new HistoryEntry();
        entry.id = "history-" + index;
        entry.timestamp = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index);
        entry.source = HistorySource.RUNNER;
        entry.collectionId = "memory-collection";
        entry.collectionName = "Memory Baseline";
        entry.folderPath = "Memory/Generated";
        entry.requestId = authored.id;
        entry.requestName = authored.name;
        entry.result = HistoryResult.SUCCESS;
        entry.statusCode = 200;
        entry.requestSent = true;

        HistoryRequestSnapshot request = HistoryRequestSnapshot.from(authored);
        request.rawRequestSent = rawHttpRequest(Math.max(HTTP_PREFIX.length, requestBytes));
        request.rawRequestSentText = new String(request.rawRequestSent, StandardCharsets.ISO_8859_1);
        request.originalRawBodyLength = request.rawRequestSent.length;
        request.storedRawBodyLength = request.rawRequestSent.length;
        entry.requestSnapshot = request;

        HistoryResponseSnapshot response = new HistoryResponseSnapshot();
        response.statusCode = 200;
        response.reasonPhrase = "OK";
        response.mimeType = "application/octet-stream";
        response.body = binaryBytes(responseBytes);
        response.originalBodyLength = responseBytes;
        response.storedBodyLength = responseBytes;
        entry.responseSnapshot = response;
        entry.requestSizeBytes = request.rawRequestSent.length;
        entry.responseSizeBytes = responseBytes;

        RedirectHop hop = new RedirectHop();
        hop.hopNumber = 1;
        hop.sourceUrl = "https://example.test/start";
        hop.targetUrl = "https://example.test/memory";
        hop.sourceMethod = "POST";
        hop.targetMethod = "POST";
        hop.statusCode = 307;
        hop.rawRequestBytes = rawHttpRequest(Math.max(HTTP_PREFIX.length, requestBytes / 2));
        hop.rawRequestText = new String(hop.rawRequestBytes, StandardCharsets.ISO_8859_1);
        hop.responseBody = binaryBytes(Math.max(0, responseBytes / 8));
        hop.followed = true;
        entry.redirectHops.add(hop);

        entry.assertions.add(new HistoryAssertionResult("status", true, "200", "200"));
        entry.extractions.add(new HistoryExtractionResult("fixture", "present"));
        entry.scriptLogs.add(new ScriptLogEntry("info", "memory fixture log", "script", "fixture"));
        entry.scriptWarnings.add("fixture warning");
        entry.scriptErrors.add("fixture error evidence");
        entry.scriptVariableMutations.add(new ScriptVariableMutation(
                "fixtureValue", "before", "after", "request", false));
        entry.unresolvedVariables.add("missing_fixture_value");
        entry.ensureDefaults();
        return entry;
    }

    public static RunnerResult runnerResult(int index, int requestBytes, int responseBytes) {
        RunnerResult result = new RunnerResult();
        result.requestId = "runner-" + index;
        result.requestName = "Runner request " + index;
        result.collectionId = "memory-collection";
        result.collectionName = "Memory Baseline";
        result.method = "GET";
        result.requestUrl = "https://example.test/runner/" + index;
        result.rawRequestBytes = rawHttpRequest(Math.max(HTTP_PREFIX.length, requestBytes));
        result.rawRequestText = new String(result.rawRequestBytes, StandardCharsets.ISO_8859_1);
        result.requestBody = "";
        result.requestSent = true;
        result.success = true;
        result.statusCode = 200;
        result.responseBody = textBytes(responseBytes);
        result.responseBodyLength = responseBytes;
        result.responseSize = responseBytes;
        result.responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: text/plain";
        return result;
    }

    public static EnvironmentProfile environment(int variableBytes) {
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = "memory-environment";
        environment.name = "Memory Baseline";
        environment.sourceFormat = "generated";
        environment.variables.put("persisted", textBytes(variableBytes));
        environment.runtimeVariables.put("runtime", textBytes(variableBytes));
        environment.oauth2.config.put("grantType", "client_credentials");
        environment.oauth2.config.put("tokenUrl", "https://example.test/oauth/token");
        environment.oauth2.config.put("clientId", "placeholder-client");
        environment.oauth2.config.put("clientSecret", "placeholder-secret");
        environment.oauth2.outputBindings.put("accessToken", "oauth2_access_token");
        return environment;
    }

    public static WorkspaceState workspace(int historyCount, int responseBytes) {
        WorkspaceState state = new WorkspaceState();
        ApiCollection collection = new ApiCollection();
        collection.id = "memory-collection";
        collection.name = "Memory Baseline";
        collection.format = "api-workbench";
        collection.requests.add(fidelityRequest("workspace-request", 1024));
        collection.scriptBlocks.add(scriptBlock("script-collection", ScriptScope.COLLECTION));
        state.collections.add(collection);
        state.environments.add(environment(256));
        state.activeEnvironmentId = "memory-environment";
        for (int i = 0; i < historyCount; i++) {
            state.historyEntries.add(historyEntry(i, 256, responseBytes));
        }
        return state;
    }

    public static long safeAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static int referenceBase64Length(byte[] value) {
        return value == null ? 0 : Base64.getEncoder().encode(value).length;
    }

    private static ScriptBlock scriptBlock(String id, ScriptScope scope) {
        ScriptBlock block = ScriptBlock.of(
                "fixture.set(\"memory\", \"baseline\");",
                ScriptDialect.LEGACY_JAVASCRIPT,
                ScriptPhase.PRE_REQUEST,
                scope);
        block.id = id;
        block.sourceFormat = "generated";
        block.order = 1;
        block.enabled = false;
        return block;
    }
}
