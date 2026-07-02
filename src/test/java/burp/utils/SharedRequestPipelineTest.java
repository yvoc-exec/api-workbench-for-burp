package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ExecutionSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedRequestPipelineTest {

    @Test
    void buildAppliesPreRequestScriptMutationsToRawRequest() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{token}}";

        ExecutionResult exec = pipeline.build(req, col, Map.of("token", "123"), null);

        assertThat(exec.errorMessage).as(exec.errorMessage).isNull();
        assertThat(exec.success).isTrue();
        assertThat(exec.builtRequest).isNull();
        assertThat(exec.rawRequestBytes).isNotNull();
        assertThat(exec.requestHeaders).contains("/123");
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/123");
        assertThat(exec.resolvedVariables).containsEntry("token", "123");
        assertThat(col.runtimeVars).doesNotContainKey("token");
    }

    @Test
    void buildDoesNotOverwriteRuntimeVarsAddedDuringExecution() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        col.runtimeVars.put("existing", "old");
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{scripted}}";

        ExecutionResult exec = pipeline.build(req, col, Map.of("scripted", "value"), null);

        assertThat(exec.success).isTrue();
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/value");
        assertThat(col.runtimeVars)
                .containsEntry("existing", "old")
                .doesNotContainKey("scripted");
    }

    @Test
    void buildAppliesRuntimeOverlayWithoutMutatingCollectionVariables() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        col.runtimeVars.put("token", "collection-token");

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{token}}";

        ExecutionResult exec = pipeline.build(req, col, Map.of("token", "active-env-token"), null);

        assertThat(exec.success).isTrue();
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/active-env-token");
        assertThat(col.runtimeVars).containsEntry("token", "collection-token");
    }

    @Test
    void buildWithRuntimeOverlayAppliesScriptMutationsThroughRuntimeSink() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        col.runtimeVars.put("token", "collection-token");

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{session}}";

        Map<String, String> changed = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();

        ExecutionResult exec = pipeline.build(
                req,
                col,
                Map.of("token", "active-token", "session", "fresh-session"),
                null,
                (collection, changedVars, removedKeys) -> {
                    changed.putAll(changedVars);
                    removed.addAll(removedKeys);
                });

        assertThat(exec.success).isTrue();
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/fresh-session");
        assertThat(changed).isEmpty();
        assertThat(removed).isEmpty();
        assertThat(col.runtimeVars).containsEntry("token", "collection-token");
        assertThat(col.runtimeVars).doesNotContainKey("session");
    }

    @Test
    void buildWithActiveEnvironmentMutatesEnvironmentAndKeepsHistoryReplaySource() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Replay Request";
        req.method = "GET";
        req.url = "http://example.com/{{replay_token}}";
        req.preRequestScripts.add(new ApiRequest.Script("js", "pm.environment.set('replay_token', pm.environment.get('seed')); pm.environment.unset('seed');"));

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Replay Env";
        activeEnvironment.variables.put("seed", "seed-value");

        ExecutionResult exec = pipeline.build(
                req,
                col,
                Map.of(),
                null,
                null,
                activeEnvironment,
                ExecutionSource.HISTORY_REPLAY);

        assertThat(exec.executionSource).isEqualTo(ExecutionSource.BUILD_PREVIEW);
        assertThat(exec.success).isTrue();
        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/seed-value");
        assertThat(activeEnvironment.variables).containsEntry("seed", "seed-value");
        assertThat(activeEnvironment.variables).doesNotContainKey("replay_token");
    }

    @Test
    void buildHonorsExactHttpModeForRunnerSourceWithoutSynthesizingHeaders() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Exact Request";
        req.method = "POST";
        req.url = "http://example.com/api";
        req.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        req.headers.add(new ApiRequest.Header("Host", "alt.example.test", false));
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer first", false));
        req.headers.add(new ApiRequest.Header("Authorization", "Bearer second", false));
        req.headers.add(new ApiRequest.Header("Connection", "close", false));
        req.headers.add(new ApiRequest.Header("Proxy-Connection", "keep-alive", false));
        req.headers.add(new ApiRequest.Header("Content-Length", "9999", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        ExecutionResult exec = pipeline.build(req, col, null, null, null, null, ExecutionSource.RUNNER);

        assertThat(exec.executionSource).isEqualTo(ExecutionSource.BUILD_PREVIEW);
        assertThat(exec.rawRequestText).contains("Host: alt.example.test");
        assertThat(exec.rawRequestText).contains("Authorization: Bearer first");
        assertThat(exec.rawRequestText).contains("Authorization: Bearer second");
        assertThat(exec.rawRequestText).contains("Connection: close");
        assertThat(exec.rawRequestText).contains("Proxy-Connection: keep-alive");
        assertThat(exec.rawRequestText).contains("Content-Length: 9999");
        assertThat(exec.rawRequestText).contains("Transfer-Encoding: chunked");
        assertThat(exec.rawRequestText).doesNotContain("User-Agent: BurpExtensionRuntime");
        assertThat(exec.rawRequestText).doesNotContain("Cache-Control: no-cache");
        assertThat(exec.rawRequestText).doesNotContain("Host: example.com");

        ApiRequest safe = new ApiRequest();
        safe.name = "Safe Request";
        safe.method = "POST";
        safe.url = "http://example.com/api";
        safe.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        safe.editorMaterialized = true;
        safe.headers.add(new ApiRequest.Header("Host", "alt.example.test", false));
        safe.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        safe.headers.add(new ApiRequest.Header("X-Keep", "yes", false));
        safe.body = new ApiRequest.Body();
        safe.body.mode = "raw";
        safe.body.raw = "hello";

        ExecutionResult safeExec = pipeline.build(safe, col, null, null, null, null, ExecutionSource.RUNNER);

        assertThat(safeExec.rawRequestText).contains("Host: example.com");
        assertThat(safeExec.rawRequestText).contains("Content-Length: 5");
        assertThat(safeExec.rawRequestText).contains("X-Keep: yes");
        assertThat(safeExec.rawRequestText).doesNotContain("Host: alt.example.test");
        assertThat(safeExec.rawRequestText).doesNotContain("Transfer-Encoding: chunked");
    }

    @Test
    void preRequestScriptCanReadCollectionVariablesThroughPostmanApi() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "collection_token";
        variable.value = "abc123";
        col.variables.add(variable);

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{header_token}}";

        ExecutionResult exec = pipeline.build(req, col, Map.of("header_token", "abc123"), null);

        assertThat(exec.requestHeaders).contains("/abc123");
        assertThat(col.runtimeVars).doesNotContainKey("header_token");
    }

    @Test
    void preRequestScriptUsesMutableRuntimeContextWithoutPromotingAllResolverVars() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ApiCollection col = new ApiCollection();
        col.name = "Collection";
        col.runtimeVars.put("runtimeOnly", "from-runtime");
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/{{envOnly}}/{{runtimeOnly}}";

        ExecutionResult exec = pipeline.build(req, col, Map.of("envOnly", "from-env"), null);

        assertThat(exec.resolvedUrl).isEqualTo("http://example.com/from-env/from-runtime");
        assertThat(col.runtimeVars)
                .containsEntry("runtimeOnly", "from-runtime")
                .doesNotContainKey("envOnly");
    }

    @Test
    void sharedPipelineInjectsOauth2TokenBeforeRequestBuilderBuildsAuthorization() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        burp.auth.OAuth2Manager manager = org.mockito.Mockito.mock(burp.auth.OAuth2Manager.class);
        burp.auth.TokenStore.TokenEntry entry = new burp.auth.TokenStore.TokenEntry();
        entry.accessToken = "pipeline-token";
        entry.expiresAt = System.currentTimeMillis() + 60_000;
        org.mockito.Mockito.when(manager.getValidToken(org.mockito.Mockito.any())).thenReturn(entry);

        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), manager);

        ApiCollection col = new ApiCollection();
        col.name = "OAuth Collection";
        col.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");
        col.runtimeOAuth2.put("oauth2_client_id", "client");
        col.runtimeOAuth2.put("oauth2_client_secret", "secret");
        col.runtimeOAuth2.put("oauth2_grant", "client_credentials");

        ApiRequest req = new ApiRequest();
        req.name = "OAuth Request";
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessToken", "{{oauth2_access_token}}");

        ExecutionResult exec = pipeline.execute(req, col, false);

        assertThat(exec.requestHeaders).contains("Authorization: Bearer pipeline-token");
        assertThat(exec.resolvedVariables).containsEntry("oauth2_access_token", "pipeline-token");
        assertThat(col.runtimeOAuth2).containsEntry("oauth2_access_token", "pipeline-token");
    }

    @Test
    void sharedPipelineStoresOauth2TokenUsingCustomSinkBinding() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        burp.auth.OAuth2Manager manager = org.mockito.Mockito.mock(burp.auth.OAuth2Manager.class);
        burp.auth.TokenStore.TokenEntry entry = new burp.auth.TokenStore.TokenEntry();
        entry.accessToken = "fresh-token";
        entry.refreshToken = "fresh-refresh";
        entry.expiresAt = System.currentTimeMillis() + 60_000;
        org.mockito.Mockito.when(manager.getValidToken(org.mockito.Mockito.any())).thenReturn(entry);

        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), manager);

        ApiCollection col = new ApiCollection();
        col.name = "OAuth Collection";
        col.runtimeVars.put("token", "stale");

        ApiRequest req = new ApiRequest();
        req.name = "OAuth Request";
        req.method = "GET";
        req.url = "http://example.com/api";
        req.auth = new ApiRequest.Auth();
        req.auth.type = "oauth2";
        req.auth.properties.put("accessToken", "{{token}}");

        Map<String, String> activeEnvironment = Map.of(
                "token", "active-env-token",
                "oauth2_token_url", "https://auth.example.test/token",
                "oauth2_client_id", "client",
                "oauth2_client_secret", "secret",
                "oauth2_grant", "client_credentials");

        ExecutionResult exec = pipeline.execute(
                req,
                col,
                false,
                activeEnvironment,
                (collection, tokenEntry) -> Map.of("oauth2_access_token", tokenEntry.accessToken, "token", tokenEntry.accessToken));

        assertThat(exec.requestHeaders).contains("Authorization: Bearer fresh-token");
        assertThat(exec.resolvedVariables)
                .containsEntry("token", "fresh-token")
                .containsEntry("oauth2_access_token", "fresh-token");
        assertThat(col.runtimeOAuth2).doesNotContainKey("token");
    }

    @Test
    void sharedPipelineHonorsAutoCompatibleBuildMode() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/api";
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "pipeline-token");

        ExecutionResult exec = pipeline.execute(req, col, false);

        assertThat(exec.requestHeaders).contains("Authorization: Bearer pipeline-token");
    }

    @Test
    void sharedPipelineHonorsManualPreserveBuildMode() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "GET";
        req.url = "http://example.com/api";
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.auth = new ApiRequest.Auth();
        req.auth.type = "bearer";
        req.auth.properties.put("token", "pipeline-token");
        req.suppressedAutoHeaders.add("authorization");

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).doesNotContain("Authorization: Bearer pipeline-token");
    }

    @Test
    void sharedPipelineDoesNotChangeTransportSemantics() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Request";
        req.method = "POST";
        req.url = "http://example.com/api";
        req.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        req.headers.add(new ApiRequest.Header("Content-Length", "9999", false));
        req.headers.add(new ApiRequest.Header("Transfer-Encoding", "chunked", false));
        req.body = new ApiRequest.Body();
        req.body.mode = "raw";
        req.body.raw = "hello";

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).doesNotContain("Transfer-Encoding: chunked");
        assertThat(exec.requestHeaders).contains("Content-Length: 5");
    }

    @Test
    void buildPreservesMultipartFileBytes() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        Path tempFile = Files.createTempFile(Path.of("target"), "pipeline-binary-", ".bin");
        byte[] fileBytes = new byte[] {0x00, (byte) 0xff, (byte) 0xfe, 0x41};
        Files.write(tempFile, fileBytes);
        tempFile.toFile().deleteOnExit();

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Upload";
        req.method = "POST";
        req.url = "http://example.com/upload";
        req.body = new ApiRequest.Body();
        req.body.mode = "formdata";
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField("file", "");
        field.fileUpload = true;
        field.filePath = tempFile.toString();
        req.body.formdata.add(field);

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.builtRequest).isNull();
        assertThat(exec.rawRequestBytes).isNotNull();
        assertThat(containsSubArray(exec.rawRequestBytes, fileBytes)).isTrue();
    }

    @Test
    void buildSplitsHeadersAndUrlEncodedBody() throws Exception {
        MontoyaApi api = mock(MontoyaApi.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.DISABLED), null);

        ApiCollection col = new ApiCollection();
        col.name = "Collection";

        ApiRequest req = new ApiRequest();
        req.name = "Submit";
        req.method = "POST";
        req.url = "http://example.com/form";
        req.body = new ApiRequest.Body();
        req.body.mode = "urlencoded";
        req.body.urlencoded.add(new ApiRequest.Body.FormField("a", "1"));
        req.body.urlencoded.add(new ApiRequest.Body.FormField("b", "2"));

        ExecutionResult exec = pipeline.build(req, col);

        assertThat(exec.success).isTrue();
        assertThat(exec.requestHeaders).contains("POST /form HTTP/1.1");
        assertThat(exec.requestHeaders).contains("Content-Type: application/x-www-form-urlencoded");
        assertThat(exec.requestHeaders).doesNotContain("a=1&b=2");
        assertThat(exec.requestBody).isEqualTo("a=1&b=2");
    }

    private static boolean containsSubArray(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
