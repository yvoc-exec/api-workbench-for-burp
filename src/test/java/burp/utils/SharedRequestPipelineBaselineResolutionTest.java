package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ExecutionSource;
import burp.scripts.UnifiedScriptRuntime;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SharedRequestPipelineBaselineResolutionTest {

    @Test
    void disabledModeResolvesAllNormalVariableLayers() {
        Scenario scenario = scenario();
        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);

        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables)
                .containsEntry("collection_env", "collection-env")
                .containsEntry("collection_var", "collection-var")
                .containsEntry("parent_folder", "parent-folder")
                .containsEntry("child_folder", "child-folder")
                .containsEntry("parent_runtime_folder", "parent-runtime-folder")
                .containsEntry("child_runtime_folder", "child-runtime-folder")
                .containsEntry("collection_runtime_oauth2", "collection-runtime-oauth2")
                .containsEntry("collection_runtime", "collection-runtime")
                .containsEntry("environment_persisted", "environment-persisted")
                .containsEntry("environment_runtime", "environment-runtime")
                .containsEntry("explicit_overlay", "explicit-overlay")
                .containsEntry("request_var", "request-var")
                .containsEntry("oauth2_access_token", "auth-token");
        assertThat(result.rawRequestText).contains("collection-env");
        assertThat(result.rawRequestText).contains("collection-var");
        assertThat(result.rawRequestText).contains("child-folder");
        assertThat(result.rawRequestText).contains("child-runtime-folder");
        assertThat(result.rawRequestText).contains("collection-runtime-oauth2");
        assertThat(result.rawRequestText).contains("collection-runtime");
        assertThat(result.rawRequestText).contains("environment-persisted");
        assertThat(result.rawRequestText).contains("environment-runtime");
        assertThat(result.rawRequestText).contains("explicit-overlay");
        assertThat(result.rawRequestText).contains("request-var");
        assertThat(result.rawRequestText).contains("auth-token");
        assertThat(result.rawRequestText).doesNotContain("{{");
        assertThat(scenario.collection.runtimeVars).containsEntry("collection_runtime", "collection-runtime");
        assertThat(scenario.environment.runtimeVariables).containsEntry("environment_runtime", "environment-runtime");
        assertThat(scenario.request.variables).extracting(variable -> variable.key).contains("request_var");
    }

    @Test
    void disabledModeResolvesAuthoredRequestVariables() {
        Scenario scenario = scenario();
        scenario.request.variables.clear();
        scenario.request.variables.add(variable("request_only", "request-value"));
        scenario.request.url = "https://example.test/{{request_only}}";
        scenario.request.headers.clear();
        scenario.request.body.raw = "request={{request_only}}";
        scenario.runtimeOverlay.clear();
        scenario.collection.variables.clear();
        scenario.environment.variables.clear();
        scenario.environment.runtimeVariables.clear();

        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables).containsEntry("request_only", "request-value");
        assertThat(result.rawRequestText).contains("request-value");
    }

    @Test
    void disabledModeResolvesAuthoredCollectionVariables() {
        Scenario scenario = scenario();
        scenario.collection.variables.clear();
        scenario.collection.variables.add(variable("collection_only", "collection-value"));
        scenario.request.url = "https://example.test/{{collection_only}}";
        scenario.request.headers.clear();
        scenario.request.body.raw = "collection={{collection_only}}";
        scenario.request.variables.clear();
        scenario.runtimeOverlay.clear();
        scenario.environment.variables.clear();
        scenario.environment.runtimeVariables.clear();

        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables).containsEntry("collection_only", "collection-value");
        assertThat(result.rawRequestText).contains("collection-value");
    }

    @Test
    void disabledModeResolvesParentAndChildFolderVariables() {
        Scenario scenario = scenario();
        scenario.collection.folderVars.clear();
        scenario.collection.runtimeFolderVars.clear();
        scenario.collection.folderVars.put("Parent", new LinkedHashMap<>(Map.of("folder_key", "parent-value", "sibling_key", "sibling-value")));
        scenario.collection.folderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("folder_key", "child-value")));
        scenario.collection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of("runtime_folder_key", "runtime-parent-value")));
        scenario.collection.runtimeFolderVars.put("Parent/Child", new LinkedHashMap<>(Map.of("runtime_folder_key", "runtime-child-value")));
        scenario.request.path = "Parent/Child/Request";
        scenario.request.url = "https://example.test/{{folder_key}}/{{runtime_folder_key}}";
        scenario.request.headers.clear();
        scenario.request.body.raw = "folder={{folder_key}},runtime={{runtime_folder_key}}";
        scenario.request.body.contentType = "text/plain";

        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables).containsEntry("folder_key", "child-value");
        assertThat(result.resolvedVariables).containsEntry("runtime_folder_key", "runtime-child-value");
        assertThat(result.rawRequestText).contains("child-value");
        assertThat(result.rawRequestText).contains("runtime-child-value");
        assertThat(result.rawRequestText).doesNotContain("sibling-value");
    }

    @Test
    void disabledModePreservesAuthRuntimeMapping() {
        Scenario scenario = scenario();
        scenario.request.variables.clear();
        scenario.collection.variables.clear();
        scenario.runtimeOverlay.clear();
        scenario.request.url = "https://example.test/{{oauth2_access_token}}";
        scenario.request.auth = new ApiRequest.Auth();
        scenario.request.auth.type = "oauth2";
        scenario.request.auth.properties.put("accessToken", "auth-token");
        scenario.request.auth.properties.put("clientId", "client");
        scenario.request.auth.properties.put("clientSecret", "secret");

        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables).containsEntry("oauth2_access_token", "auth-token");
        assertThat(result.rawRequestText).contains("Authorization: Bearer auth-token");
    }

    @Test
    void limitedModePreservesCompleteBaselineResolution() {
        Scenario scenario = scenario();
        scenario.request.preRequestScripts.clear();
        scenario.request.preRequestScripts.add(new ApiRequest.Script("js", "pm.environment.set('blocked', 'should-not-run');"));

        SharedRequestPipeline pipeline = pipeline(ScriptMode.LIMITED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables)
                .containsEntry("collection_env", "collection-env")
                .containsEntry("collection_var", "collection-var")
                .containsEntry("request_var", "request-var")
                .containsEntry("oauth2_access_token", "auth-token");
        assertThat(scenario.environment.variables).doesNotContainKey("blocked");
    }

    @Test
    void unavailableUnifiedRuntimeFallsBackToCompleteBaselineResolver() {
        Scenario scenario = scenario();
        scenario.request.preRequestScripts.clear();
        scenario.request.preRequestScripts.add(new ApiRequest.Script("js", "pm.environment.set('blocked', 'should-not-run');"));

        UnifiedScriptRuntime unified = mock(UnifiedScriptRuntime.class);
        try (SharedRequestPipeline pipeline = new SharedRequestPipeline(
                mock(MontoyaApi.class, RETURNS_DEEP_STUBS),
                new RequestBuilder(null),
                new ScriptEngine(null, ScriptMode.FULL_JS),
                null,
                unified)) {
            org.mockito.Mockito.when(unified.isEnabled()).thenReturn(false);
            org.mockito.Mockito.when(unified.getEngineName()).thenReturn("Unavailable");

            ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

            assertThat(result.resolvedVariables)
                    .containsEntry("collection_env", "collection-env")
                    .containsEntry("collection_var", "collection-var")
                    .containsEntry("request_var", "request-var")
                    .containsEntry("oauth2_access_token", "auth-token");
            assertThat(scenario.environment.variables).doesNotContainKey("blocked");
            verify(unified, never()).executePreRequest(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
        }
    }

    @Test
    void noScriptBaselineMatchesRuntimeResolverFactoryPrecedence() {
        Scenario scenario = scenario();
        VariableResolver expected = RuntimeResolverFactory.build(
                scenario.collection,
                scenario.request,
                scenario.environment,
                scenario.runtimeOverlay
        );

        SharedRequestPipeline pipeline = pipeline(ScriptMode.DISABLED);
        ExecutionResult result = pipeline.build(scenario.request, scenario.collection, scenario.runtimeOverlay, null, null, scenario.environment, ExecutionSource.BUILD_PREVIEW);

        assertThat(result.resolvedVariables).isEqualTo(expected.getVariables());
    }

    private static SharedRequestPipeline pipeline(ScriptMode scriptMode) {
        return new SharedRequestPipeline(mock(MontoyaApi.class, RETURNS_DEEP_STUBS), new RequestBuilder(null), new ScriptEngine(null, scriptMode), null);
    }

    private static Scenario scenario() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.environment.put("collection_env", "collection-env");
        collection.variables.add(variable("collection_var", "collection-var"));
        collection.folderVars.put("Parent", new LinkedHashMap<>(Map.of(
                "folder_key", "parent-value",
                "parent_folder", "parent-folder"
        )));
        collection.folderVars.put("Parent/Child", new LinkedHashMap<>(Map.of(
                "folder_key", "child-value",
                "child_folder", "child-folder"
        )));
        collection.runtimeFolderVars.put("Parent", new LinkedHashMap<>(Map.of(
                "runtime_folder_key", "runtime-parent-value",
                "parent_runtime_folder", "parent-runtime-folder"
        )));
        collection.runtimeFolderVars.put("Parent/Child", new LinkedHashMap<>(Map.of(
                "runtime_folder_key", "runtime-child-value",
                "child_runtime_folder", "child-runtime-folder"
        )));
        collection.runtimeOAuth2.put("collection_runtime_oauth2", "collection-runtime-oauth2");
        collection.runtimeVars.put("collection_runtime", "collection-runtime");

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Environment";
        environment.variables.put("environment_persisted", "environment-persisted");
        environment.runtimeVariables.put("environment_runtime", "environment-runtime");

        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.method = "POST";
        request.url = "https://example.test/{{collection_env}}/{{collection_var}}/{{parent_folder}}/{{child_folder}}/{{parent_runtime_folder}}/{{child_runtime_folder}}/{{collection_runtime_oauth2}}/{{collection_runtime}}/{{environment_persisted}}/{{environment_runtime}}/{{explicit_overlay}}/{{request_var}}/{{oauth2_access_token}}";
        request.path = "Parent/Child/Request";
        request.variables.add(variable("request_var", "request-var"));
        request.headers.add(new ApiRequest.Header("X-Resolved", "{{collection_env}}/{{collection_var}}/{{parent_folder}}/{{child_folder}}/{{parent_runtime_folder}}/{{child_runtime_folder}}/{{collection_runtime_oauth2}}/{{collection_runtime}}/{{environment_persisted}}/{{environment_runtime}}/{{explicit_overlay}}/{{request_var}}/{{oauth2_access_token}}", false));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "body={{collection_env}}/{{collection_var}}/{{parent_folder}}/{{child_folder}}/{{parent_runtime_folder}}/{{child_runtime_folder}}/{{collection_runtime_oauth2}}/{{collection_runtime}}/{{environment_persisted}}/{{environment_runtime}}/{{explicit_overlay}}/{{request_var}}/{{oauth2_access_token}}";
        request.body.contentType = "text/plain";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "auth-token");

        Map<String, String> runtimeOverlay = new LinkedHashMap<>();
        runtimeOverlay.put("explicit_overlay", "explicit-overlay");

        return new Scenario(collection, environment, request, runtimeOverlay);
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.type = "string";
        variable.enabled = true;
        return variable;
    }

    private record Scenario(ApiCollection collection,
                            EnvironmentProfile environment,
                            ApiRequest request,
                            Map<String, String> runtimeOverlay) {
    }
}
