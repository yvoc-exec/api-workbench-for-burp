package burp.testsupport;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.history.HistoryEntry;
import burp.history.HistoryHeader;
import burp.history.HistoryResult;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.utils.ExecutionResult;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public final class HistoryTestFixtures {
    public static final String COLLECTION_NAME = "Petstore";
    public static final String ENVIRONMENT_ID = "env-dev";
    public static final String ENVIRONMENT_NAME = "Dev";
    public static final String REQUEST_ID = "req-login";
    public static final String SECOND_REQUEST_ID = "req-users";
    public static final String REQUEST_NAME = "Login";
    public static final String SECOND_REQUEST_NAME = "List Users";
    public static final String REQUEST_FOLDER = "Auth";
    public static final String SECOND_REQUEST_FOLDER = "Users";
    public static final String BASE_URL = "https://api.example.test";

    private HistoryTestFixtures() {
    }

    public static ApiCollection sampleCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = COLLECTION_NAME;
        collection.description = "Sample API collection";
        collection.environment.put("base_url", BASE_URL);
        collection.environment.put("token", "collection-token");
        collection.environment.put("password", "collection-password");
        collection.requests.add(sampleRequest());
        collection.requests.add(secondRequest());
        return collection;
    }

    public static ApiRequest sampleRequest() {
        ApiRequest request = new ApiRequest();
        request.id = REQUEST_ID;
        request.name = REQUEST_NAME;
        request.path = REQUEST_FOLDER;
        request.sourceCollection = COLLECTION_NAME;
        request.method = "POST";
        request.url = "{{base_url}}/login";
        request.description = "Login request";
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.authOverrideMode = "explicit";
        request.auth = auth("bearer", Map.of("token", "{{token}}"));
        request.explicitAuth = auth("bearer", Map.of("token", "{{token}}"));
        request.headers = new ArrayList<>();
        request.headers.add(header("Authorization", "Bearer {{token}}"));
        request.headers.add(header("Content-Type", "application/json"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"demo\",\"password\":\"{{password}}\"}";
        request.body.contentType = "application/json";
        request.variables = new ArrayList<>();
        request.variables.add(variable("request_id", "req-1"));
        request.sequenceOrder = 1;
        return request;
    }

    public static ApiRequest secondRequest() {
        ApiRequest request = new ApiRequest();
        request.id = SECOND_REQUEST_ID;
        request.name = SECOND_REQUEST_NAME;
        request.path = SECOND_REQUEST_FOLDER;
        request.sourceCollection = COLLECTION_NAME;
        request.method = "GET";
        request.url = "{{base_url}}/users/{{user_id}}";
        request.headers = new ArrayList<>();
        request.headers.add(header("Accept", "application/json"));
        request.sequenceOrder = 2;
        return request;
    }

    public static EnvironmentProfile sampleEnvironment() {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.id = ENVIRONMENT_ID;
        profile.name = ENVIRONMENT_NAME;
        profile.sourceFormat = "api-workbench";
        profile.sourceFileName = "dev.env";
        profile.variables.put("base_url", BASE_URL);
        profile.variables.put("token", "env-token");
        profile.variables.put("password", "env-password");
        profile.variables.put("user_id", "42");
        return profile;
    }

    public static ExecutionResult sampleWorkbenchExecutionResult() {
        ExecutionResult exec = new ExecutionResult();
        exec.success = true;
        exec.elapsedMs = 143L;
        exec.response = mockResponseResponse(200, "{\"ok\":true}", "application/json");
        exec.requestHeaders = "Authorization: Bearer {{token}}\nContent-Type: application/json";
        exec.requestBody = "{\"username\":\"demo\",\"password\":\"{{password}}\"}";
        exec.rawRequestBytes = ("POST /login HTTP/1.1\r\nHost: api.example.test\r\n" +
                "Authorization: Bearer {{token}}\r\n" +
                "Content-Type: application/json\r\n\r\n{\"username\":\"demo\",\"password\":\"{{password}}\"}")
                .getBytes(StandardCharsets.UTF_8);
        exec.resolvedVariables.put("base_url", BASE_URL);
        exec.resolvedVariables.put("token", "env-token");
        exec.assertions.add(new RunnerResult.AssertionResult("Status is 200", true, "200", "200"));
        exec.extractedVars.put("session", "abc123");
        return exec;
    }

    public static RunnerResult sampleRunnerResult(int attemptNumber, int totalAttempts, boolean success, int statusCode, String errorMessage) {
        RunnerResult result = new RunnerResult();
        result.requestId = REQUEST_ID;
        result.requestName = REQUEST_NAME;
        result.collectionName = COLLECTION_NAME;
        result.folderPath = REQUEST_FOLDER;
        result.method = "POST";
        result.requestUrl = "{{base_url}}/login";
        result.requestHeaders = "Authorization: Bearer {{token}}\nContent-Type: application/json";
        result.requestBody = "{\"username\":\"demo\",\"password\":\"{{password}}\"}";
        result.rawRequestBytes = ("POST /login HTTP/1.1\r\nHost: api.example.test\r\n" +
                "Authorization: Bearer {{token}}\r\n" +
                "Content-Type: application/json\r\n\r\n{\"username\":\"demo\",\"password\":\"{{password}}\"}")
                .getBytes(StandardCharsets.UTF_8);
        result.rawRequestText = new String(result.rawRequestBytes, StandardCharsets.UTF_8);
        result.success = success;
        result.statusCode = statusCode;
        result.responseTimeMs = 321L;
        result.responseSize = 19;
        result.responseBodyLength = 19;
        result.responseHeaders = "HTTP/1.1 " + statusCode + (statusCode >= 400 ? " Error" : " OK") + "\nContent-Type: application/json";
        result.responseBody = success ? "{\"ok\":true}" : "{\"error\":\"boom\"}";
        result.errorMessage = errorMessage;
        result.assertions.add(new RunnerResult.AssertionResult("Status is 200", success, "200", String.valueOf(statusCode)));
        result.extractedVariables.put("session", "abc123");
        result.resolvedVariables.put("base_url", BASE_URL);
        result.resolvedVariables.put("token", "env-token");
        result.attemptNumber = attemptNumber;
        result.totalAttempts = totalAttempts;
        return result;
    }

    public static RunnerResult sampleRunnerResult() {
        return sampleRunnerResult(1, 1, true, 200, null);
    }

    public static HistoryEntry sampleWorkbenchEntry() {
        HistoryEntry entry = HistoryEntry.fromWorkbenchExecution(
                sampleCollection(),
                sampleRequest(),
                sampleEnvironment(),
                sampleWorkbenchExecutionResult(),
                1,
                1,
                List.of("missing_password"));
        entry.id = "history-workbench";
        entry.timestamp = Instant.parse("2026-06-15T01:24:05Z");
        return entry;
    }

    public static HistoryEntry sampleRunnerEntry() {
        HistoryEntry entry = HistoryEntry.fromRunnerAttempt(
                sampleCollection(),
                sampleRequest(),
                sampleEnvironment(),
                sampleRunnerResult(2, 3, false, 500, "Missing variable: base_url"));
        entry.id = "history-runner";
        entry.timestamp = Instant.parse("2026-06-15T01:25:05Z");
        return entry;
    }

    public static HistoryEntry sampleDiffEntry() {
        HistoryEntry entry = HistoryEntry.copyOf(sampleWorkbenchEntry());
        entry.id = "history-diff";
        entry.timestamp = Instant.parse("2026-06-15T01:26:05Z");
        return entry;
    }

    public static HistoryEntry copyEntry(HistoryEntry source, String id, Instant timestamp) {
        HistoryEntry copy = HistoryEntry.copyOf(source);
        if (copy != null) {
            copy.id = id;
            copy.timestamp = timestamp;
        }
        return copy;
    }

    public static ApiRequest copyRequest(ApiRequest source) {
        if (source == null) {
            return null;
        }
        ApiRequest copy = new ApiRequest();
        copy.id = source.id;
        copy.name = source.name;
        copy.path = source.path;
        copy.sourceCollection = source.sourceCollection;
        copy.method = source.method;
        copy.url = source.url;
        copy.description = source.description;
        copy.editorMaterialized = source.editorMaterialized;
        copy.buildMode = source.buildMode;
        copy.authOverrideMode = source.authOverrideMode;
        copy.auth = source.auth;
        copy.explicitAuth = source.explicitAuth;
        copy.headers = source.headers != null ? new ArrayList<>(source.headers) : new ArrayList<>();
        copy.body = source.body;
        copy.variables = source.variables != null ? new ArrayList<>(source.variables) : new ArrayList<>();
        copy.scriptBlocks = source.scriptBlocks != null ? new ArrayList<>(source.scriptBlocks) : new ArrayList<>();
        return copy;
    }

    public static HistoryRequestSnapshot requestSnapshot(ApiRequest request) {
        return HistoryRequestSnapshot.from(request);
    }

    public static HistoryResponseSnapshot responseSnapshot(int statusCode, String body, String mimeType) {
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        snapshot.statusCode = statusCode;
        snapshot.reasonPhrase = statusCode >= 400 ? "Error" : "OK";
        snapshot.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        snapshot.mimeType = mimeType;
        snapshot.headers = new ArrayList<>();
        snapshot.headers.add(new HistoryHeader("Content-Type", mimeType != null ? mimeType : "text/plain"));
        return snapshot;
    }

    public static HttpRequestResponse mockResponseResponse(int statusCode, String body, String contentType) {
        HttpResponse response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) statusCode);
        ByteArray bodyBytes = Mockito.mock(ByteArray.class);
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        when(bodyBytes.getBytes()).thenReturn(bytes);
        when(bodyBytes.length()).thenReturn(bytes.length);
        when(response.body()).thenReturn(bodyBytes);
        when(response.bodyToString()).thenReturn(body != null ? body : "");
        burp.api.montoya.http.message.HttpHeader responseHeader = Mockito.mock(burp.api.montoya.http.message.HttpHeader.class);
        when(responseHeader.name()).thenReturn("Content-Type");
        when(responseHeader.value()).thenReturn(contentType != null ? contentType : "text/plain");
        when(responseHeader.toString()).thenReturn("Content-Type: " + (contentType != null ? contentType : "text/plain"));
        when(response.headers()).thenReturn(List.of(responseHeader));

        HttpRequestResponse responseWrapper = Mockito.mock(HttpRequestResponse.class);
        when(responseWrapper.response()).thenReturn(response);
        when(responseWrapper.withAnnotations(any(Annotations.class))).thenReturn(responseWrapper);
        when(responseWrapper.request()).thenReturn(Mockito.mock(HttpRequest.class));
        return responseWrapper;
    }

    private static ApiRequest.Header header(String key, String value) {
        return new ApiRequest.Header(key, value, false);
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.enabled = true;
        return variable;
    }

    private static ApiRequest.Auth auth(String type, Map<String, String> properties) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = type;
        if (properties != null) {
            auth.properties.putAll(properties);
        }
        return auth;
    }
}
