package burp.utils;

import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SharedRequestPipelinePreviewIsolationTest {
    @Test
    void previewScriptMutationChangesBuiltCopyButNotLiveRequest() {
        ApiCollection collection = collection("awb.request.url = 'https://example.test/preview';");
        ApiRequest request = request();
        ExecutionResult result = pipeline(null).build(request, collection, null, null, null, null);
        assertThat(result.rawRequestText).contains("GET /preview HTTP/1.1");
        assertThat(request.url).isEqualTo("https://example.test/live");
    }

    @Test
    void previewDoesNotCommitCollectionRuntimeVariables() {
        ApiCollection collection = collection("awb.collection.set('token','preview');");
        pipeline(null).build(request(), collection, null, null, null, null);
        assertThat(collection.runtimeVars).doesNotContainKey("token");
    }

    @Test
    void previewDoesNotCommitEnvironmentVariables() {
        ApiCollection collection = collection("awb.environment.set('token','preview');");
        EnvironmentProfile environment = new EnvironmentProfile();
        pipeline(null).build(request(), collection, null, null, null, environment);
        assertThat(environment.variables).doesNotContainKey("token");
        assertThat(environment.runtimeVariables).doesNotContainKey("token");
    }

    @Test
    void previewDoesNotAcquireOAuth2Token() {
        CountingOAuth2Manager manager = new CountingOAuth2Manager();
        ApiRequest request = request();
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        ApiCollection collection = collection("");
        collection.environment.putAll(Map.of(
                "oauth2_grant", "client_credentials",
                "oauth2_token_url", "https://auth.example.test/token",
                "oauth2_client_id", "id",
                "oauth2_client_secret", "secret"
        ));
        pipeline(manager).build(request, collection, null, null, null, null);
        assertThat(manager.calls.get()).isZero();
    }

    @Test
    void previewDoesNotExecuteDependentRequest() {
        AtomicInteger calls = new AtomicInteger();
        ApiCollection collection = collection("pm.execution.runRequest('child');");
        pipeline(null).build(request(), collection, null, null, null, null, ExecutionSource.RUNNER,
                new ScriptDependentRequestExecutor() {
                    @Override
                    public ScriptDependentRequestResult runRequest(ScriptExecutionContext context, String target) {
                        calls.incrementAndGet();
                        return ScriptDependentRequestResult.ignored(target);
                    }

                    @Override
                    public ScriptDependentRequestResult sendAdHocRequest(ScriptExecutionContext context, ScriptAdHocRequest request) {
                        calls.incrementAndGet();
                        return ScriptDependentRequestResult.ignored("adhoc");
                    }
                });
        assertThat(calls).hasValue(0);
    }

    private SharedRequestPipeline pipeline(OAuth2Manager oauth2Manager) {
        return new SharedRequestPipeline(null, new RequestBuilder(null), new ScriptEngine(null, ScriptMode.FULL_JS), oauth2Manager);
    }

    private ApiCollection collection(String script) {
        ApiCollection collection = new ApiCollection();
        collection.name = "c";
        collection.scriptBlocks = new ArrayList<>();
        ScriptBlock block = ScriptBlock.of(script, ScriptDialect.API_WORKBENCH, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION);
        if (script.contains("pm.")) {
            block.dialect = ScriptDialect.POSTMAN;
        }
        collection.scriptBlocks.add(block);
        return collection;
    }

    private ApiRequest request() {
        ApiRequest request = new ApiRequest();
        request.id = "r1";
        request.name = "r";
        request.method = "GET";
        request.url = "https://example.test/live";
        return request;
    }

    static class CountingOAuth2Manager extends OAuth2Manager {
        final AtomicInteger calls = new AtomicInteger();
        CountingOAuth2Manager() { super(null); }
        @Override
        public TokenStore.TokenEntry getValidToken(OAuth2Config config) {
            calls.incrementAndGet();
            TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
            entry.accessToken = "x";
            return entry;
        }
    }
}
