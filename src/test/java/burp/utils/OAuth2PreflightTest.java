package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.auth.OAuth2Config;
import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.api.montoya.http.RequestOptions;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RedirectPolicy;
import burp.scripts.ExecutionSource;
import burp.scripts.ScriptDependentRequestExecutor;
import burp.scripts.ScriptExecutionResult;
import burp.utils.ScriptMode;
import burp.scripts.UnifiedScriptRuntime;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class OAuth2PreflightTest {

    @AfterEach
    void clearTokens() {
        TokenStore.clearAll();
    }

    @Test
    void invalidOAuth2ConfigurationAbortsByDefault() {
        Harness harness = harness(failingManager("boom"));
        harness.collection.runtimeOAuth2.remove("oauth2_token_url");
        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE);
        assertThat(result.isBlockedBeforeSend()).isTrue();
    }

    @Test
    void oauth2AcquisitionFailureAbortsByDefault() {
        Harness harness = harness(failingManager("acquire failed"));
        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE);
        assertThat(result.requestSent).isFalse();
    }

    @Test
    void oauth2NullTokenAbortsByDefault() {
        Harness harness = harness(nullTokenManager());
        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment
        );

        assertThat(harness.sendCount.get()).isZero();
        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE);
    }

    @Test
    void staleTokenPolicyUsesExpiredTokenStoreEntry() {
        Harness harness = harness(failingManager("stale"));
        TokenStore.TokenEntry entry = tokenEntry("stale-token");
        TokenStore.store(TokenStore.makeKey(OAuth2Config.fromVariables(harness.collection.runtimeOAuth2)), entry);
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment,
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null
        );
        System.out.println("SEND WITHOUT status=" + result.preflightStatus + " sent=" + result.requestSent + " count=" + harness.sendCount.get() + " error=" + result.errorMessage);

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.oauth2UsedStaleToken).isTrue();
        assertThat(result.requestSent).isTrue();
    }

    @Test
    void staleTokenPolicyUsesExistingRuntimeTokenWhenStoreEmpty() {
        Harness harness = harness(failingManager("stale"));
        harness.collection.runtimeOAuth2.put("oauth2_access_token", "runtime-stale");
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment,
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null
        );
        System.out.println("SEND WITHOUT status=" + result.preflightStatus + " sent=" + result.requestSent + " count=" + harness.sendCount.get() + " error=" + result.errorMessage);

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.oauth2UsedStaleToken).isTrue();
    }

    @Test
    void staleTokenPolicyRequiresActualNonBlankToken() {
        Harness harness = harness(failingManager("stale"));
        harness.collection.runtimeOAuth2.put("oauth2_access_token", "   ");
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.USE_STALE_TOKEN;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment,
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null
        );

        assertThat(result.preflightStatus).isEqualTo(ExecutionPreflightStatus.BLOCKED_OAUTH2_FAILURE);
        assertThat(harness.sendCount.get()).isZero();
    }

    @Test
    void sendWithoutTokenRemovesOnlyGeneratedOAuth2Auth() {
        Harness harness = harness(failingManager("send without"));
        harness.request.headers = new java.util.ArrayList<>();
        harness.request.headers.add(new ApiRequest.Header("Authorization", "Bearer authored"));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.SEND_WITHOUT_TOKEN;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment,
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null
        );

        assertThat(harness.sendCount.get()).isEqualTo(1);
        assertThat(result.oauth2SentWithoutToken).isTrue();
    }

    @Test
    void sendWithoutTokenPreservesAuthoredAuthorizationHeader() {
        Harness harness = harness(failingManager("send without"));
        harness.request.headers = new java.util.ArrayList<>();
        harness.request.headers.add(new ApiRequest.Header("Authorization", "Bearer authored"));
        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.oauth2FailureMode = ExecutionPolicy.OAuth2FailureMode.SEND_WITHOUT_TOKEN;
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null, null, null, harness.environment,
                ExecutionSource.WORKBENCH_SEND, null, RedirectPolicy.defaults(), policy, null
        );

        assertThat(result.requestHeaders).contains("Authorization: Bearer authored");
    }

    @Test
    void successfulOAuth2TokenIsStoredOnlyAfterAcceptedPreflight() {
        Harness harness = harness(successManager());
        AtomicInteger sinkCalls = new AtomicInteger();
        ExecutionResult result = harness.pipeline.execute(
                harness.request, harness.collection, false, null,
                (collection, entry) -> {
                    sinkCalls.incrementAndGet();
                    return Map.of("oauth2_access_token", entry.accessToken);
                },
                null,
                harness.environment
        );
        assertThat(result.requestSent).isTrue();
        assertThat(sinkCalls.get()).isEqualTo(1);
        assertThat(harness.sendCount.get()).isEqualTo(1);
    }

    @Test
    void deniedConfirmationDoesNotCallOAuth2TokenSink() {
        Harness harness = harness(successManager());
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        harness.request.url = "https://example.test/{{missing}}";

        ExecutionPolicy policy = ExecutionPolicy.workbenchDefaults();
        policy.normalize();

        ExecutionResult result = harness.pipeline.execute(
                harness.request,
                harness.collection,
                false,
                null,
                (collection, entry) -> {
                    sinkCalls.incrementAndGet();
                    return Map.of("oauth2_access_token", entry.accessToken);
                },
                null,
                harness.environment,
                ExecutionSource.WORKBENCH_SEND,
                null,
                RedirectPolicy.defaults(),
                policy,
                preflight -> {
                    handlerCalls.incrementAndGet();
                    return false;
                }
        );

        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(result.isBlockedBeforeSend()).isTrue();
        assertThat(sinkCalls.get()).isZero();
        assertThat(harness.sendCount.get()).isZero();
    }

    private static Harness harness(OAuth2Manager manager) {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> sentRequests = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(sendCount, sentRequests, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain"));
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeOAuth2.put("oauth2_token_url", "https://oauth2.test/token");
        collection.runtimeOAuth2.put("oauth2_client_id", "client-id");
        collection.runtimeOAuth2.put("oauth2_client_secret", "client-secret");
        collection.runtimeOAuth2.put("oauth2_grant", "client_credentials");
        ApiRequest request = oauth2Request();
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Env";
        SharedRequestPipeline pipeline = new SharedRequestPipeline(
                api,
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                manager,
                new StubRuntime(api),
                timeout -> Mockito.mock(RequestOptions.class)
        );
        return new Harness(pipeline, collection, request, environment, sendCount, sentRequests);
    }

    private static OAuth2Manager failingManager(String message) {
        OAuth2Manager manager = Mockito.mock(OAuth2Manager.class);
        try {
            when(manager.getValidToken(any(OAuth2Config.class))).thenThrow(new Exception(message));
        } catch (Exception ignored) {
        }
        return manager;
    }

    private static OAuth2Manager nullTokenManager() {
        OAuth2Manager manager = Mockito.mock(OAuth2Manager.class);
        try {
            when(manager.getValidToken(any(OAuth2Config.class))).thenReturn(null);
        } catch (Exception ignored) {
        }
        return manager;
    }

    private static OAuth2Manager successManager() {
        OAuth2Manager manager = Mockito.mock(OAuth2Manager.class);
        try {
            when(manager.getValidToken(any(OAuth2Config.class))).thenReturn(tokenEntry("fresh-token"));
        } catch (Exception ignored) {
        }
        return manager;
    }

    private static ApiRequest oauth2Request() {
        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.test/";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties = new LinkedHashMap<>();
        request.auth.properties.put("accessToken", "{{oauth2_access_token}}");
        return request;
    }

    private static ApiRequest copyRequest(ApiRequest source) {
        ApiRequest copy = new ApiRequest();
        copy.id = source.id;
        copy.name = source.name;
        copy.method = source.method;
        copy.url = source.url;
        copy.auth = source.auth;
        copy.headers = source.headers != null ? new java.util.ArrayList<>(source.headers) : new java.util.ArrayList<>();
        return copy;
    }

    private static String rawRequestText(burp.api.montoya.http.message.requests.HttpRequest request) {
        return request != null && request.toByteArray() != null
                ? new String(request.toByteArray().getBytes(), StandardCharsets.ISO_8859_1)
                : "";
    }

    private static TokenStore.TokenEntry tokenEntry(String token) {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = token;
        entry.tokenType = "Bearer";
        entry.acquiredAt = System.currentTimeMillis();
        entry.expiresAt = System.currentTimeMillis() + 60_000L;
        return entry;
    }

    private record Harness(SharedRequestPipeline pipeline,
                           ApiCollection collection,
                           ApiRequest request,
                           EnvironmentProfile environment,
                           AtomicInteger sendCount,
                           CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> sentRequests) {
    }

    private static final class StubRuntime extends UnifiedScriptRuntime {
        StubRuntime(MontoyaApi api) {
            super(api, ScriptMode.FULL_JS);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ScriptExecutionResult executePreRequest(ApiCollection collection,
                                                       ApiRequest request,
                                                       EnvironmentProfile activeEnvironment,
                                                       ExecutionSource executionSource,
                                                       int attemptNumber,
                                                       ScriptDependentRequestExecutor dependentRequestExecutor,
                                                       Map<String, String> runtimeOverlay) {
            ScriptExecutionResult result = new ScriptExecutionResult();
            result.success = true;
            if (collection != null && collection.runtimeOAuth2 != null) {
                result.effectiveVariables.putAll(collection.runtimeOAuth2);
            }
            if (activeEnvironment != null && activeEnvironment.variables != null) {
                result.effectiveVariables.putAll(activeEnvironment.variables);
            }
            if (runtimeOverlay != null) {
                result.effectiveVariables.putAll(runtimeOverlay);
            }
            return result;
        }
    }
}
