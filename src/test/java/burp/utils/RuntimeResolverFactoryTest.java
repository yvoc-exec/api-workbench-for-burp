package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.exporter.ExportVariableResolutionService;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeResolverFactoryTest {

    @Test
    void runtimeResolverFactoryPreservesSharedPipelinePrecedence() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.environment.put("shared", "env");
        collection.variables.add(variable("shared", "collection"));
        collection.folderVars.put("FolderA", new LinkedHashMap<>(Map.of("shared", "folder")));
        collection.runtimeOAuth2.put("shared", "runtime-oauth2");
        collection.runtimeVars.put("shared", "runtime-vars");

        ApiRequest request = new ApiRequest();
        request.name = "Req";
        request.path = "FolderA/Req";
        request.url = "https://example.test/{{shared}}";
        request.variables.add(variable("shared", "request"));
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "auth-token");

        VariableResolver resolver = RuntimeResolverFactory.build(
                collection,
                request,
                RuntimeResolverFactory.Options.withRuntimeVariableOverlay(Map.of("shared", "overlay"))
        );

        // This precedence mirrors SharedRequestPipeline execution resolution.
        // Changing it changes runtime behavior and should be reviewed carefully.
        assertThat(resolver.resolve("{{shared}}")).isEqualTo("request");
        assertThat(resolver.resolve("{{oauth2_access_token}}")).isEqualTo("auth-token");
    }

    @Test
    void runtimeResolverFactoryMapsAuthRuntimeVarsLikeSharedPipeline() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeOAuth2.put("oauth2_access_token", "stale-token");

        ApiRequest request = new ApiRequest();
        request.name = "OAuth Request";
        request.url = "https://example.test";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "oauth2";
        request.auth.properties.put("accessToken", "mapped-token");

        VariableResolver resolver = RuntimeResolverFactory.build(collection, request);

        assertThat(resolver.getVariables()).containsEntry("oauth2_access_token", "mapped-token");
        assertThat(resolver.resolve("{{oauth2_access_token}}")).isEqualTo("mapped-token");
    }

    @Test
    void runtimeResolverFactoryAppliesRuntimeOverlayForActiveVariablesDraft() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeVars.put("token", "old");

        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.url = "https://example.test/{{token}}";

        VariableResolver resolver = RuntimeResolverFactory.build(
                collection,
                request,
                RuntimeResolverFactory.Options.withRuntimeVariableOverlay(Map.of("token", "draft"))
        );

        assertThat(resolver.resolve("{{token}}")).isEqualTo("draft");
    }

    @Test
    void runtimeResolverFactoryAppliesActiveEnvironmentOverlayWithoutChangingCollectionState() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeVars.put("token", "collection-token");

        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.url = "https://example.test/{{token}}";

        VariableResolver resolver = RuntimeResolverFactory.build(
                collection,
                request,
                RuntimeResolverFactory.Options.withRuntimeVariableOverlay(Map.of("token", "active-env-token"))
        );

        assertThat(resolver.resolve("{{token}}")).isEqualTo("active-env-token");
        assertThat(collection.runtimeVars).containsEntry("token", "collection-token");
    }

    @Test
    void runtimeResolverFactoryPreservesRequestVariablePrecedence() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        collection.runtimeVars.put("id", "runtime");

        ApiRequest request = new ApiRequest();
        request.name = "Request";
        request.url = "https://example.test/{{id}}";
        request.variables.add(variable("id", "request"));

        VariableResolver resolver = RuntimeResolverFactory.build(
                collection,
                request,
                RuntimeResolverFactory.Options.withRuntimeVariableOverlay(Map.of("id", "overlay"))
        );

        assertThat(resolver.resolve("{{id}}")).isEqualTo("request");
    }

    @Test
    void disabledCollectionAndRequestVariablesDoNotResolveOrAppearInInspection() {
        ApiCollection collection = new ApiCollection();
        collection.environment.put("shared", "environment");
        ApiRequest.Variable disabledCollection = variable("shared", "collection-disabled");
        disabledCollection.enabled = false;
        collection.variables.add(disabledCollection);
        ApiRequest request = new ApiRequest();
        request.url = "https://example.test/{{shared}}";
        ApiRequest.Variable disabledRequest = variable("shared", "request-disabled");
        disabledRequest.enabled = false;
        request.variables.add(disabledRequest);

        VariableResolver resolver = RuntimeResolverFactory.build(collection, request);
        RuntimeResolverFactory.ResolutionTrace trace = RuntimeResolverFactory.inspect(
                collection, request, null, Map.of(), "shared");

        assertThat(resolver.resolve("{{shared}}")).isEqualTo("environment");
        assertThat(trace.value).isEqualTo("environment");
        assertThat(trace.candidates).extracting(candidate -> candidate.source)
                .containsExactly("collection environment");
    }

    @Test
    void exportAndRuntimeResolversAgreeThatDisabledVariablesAreAbsent() {
        ApiCollection collection = new ApiCollection();
        ApiRequest.Variable disabledCollection = variable("collection_disabled", "secret");
        disabledCollection.enabled = false;
        collection.variables.add(disabledCollection);
        ApiRequest request = new ApiRequest();
        ApiRequest.Variable disabledRequest = variable("request_disabled", "secret");
        disabledRequest.enabled = false;
        request.variables.add(disabledRequest);

        VariableResolver runtime = RuntimeResolverFactory.build(collection, request);
        VariableResolver export = ExportVariableResolutionService.buildResolver(
                collection, request, null, Map.of());

        assertThat(runtime.resolve("{{collection_disabled}}/{{request_disabled}}"))
                .isEqualTo("{{collection_disabled}}/{{request_disabled}}");
        assertThat(export.resolve("{{collection_disabled}}/{{request_disabled}}"))
                .isEqualTo("{{collection_disabled}}/{{request_disabled}}");
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
