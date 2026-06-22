package burp.utils;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeResolverFactoryInspectTest {

    @Test
    void inspectReportsWinningAndShadowedSourcesAcrossRuntimeLayers() {
        ApiCollection collection = new ApiCollection();
        collection.environment.put("shared", "collection-env");
        collection.variables.add(variable("shared", "collection-var"));
        collection.folderVars.put("Auth", new LinkedHashMap<>(Map.of("shared", "folder-var")));
        collection.runtimeOAuth2.put("shared", "runtime-oauth2");
        collection.runtimeVars.put("shared", "runtime-var");

        ApiRequest request = new ApiRequest();
        request.name = "Login";
        request.path = "Auth/Login";
        request.url = "https://example.test/{{shared}}";
        request.variables.add(variable("shared", "request-var"));

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "UAT";
        activeEnvironment.variables.put("shared", "active-env");

        RuntimeResolverFactory.ResolutionTrace trace = RuntimeResolverFactory.inspect(
                collection,
                request,
                activeEnvironment,
                Map.of("shared", "overlay-var"),
                "shared"
        );

        assertThat(trace.resolved).isTrue();
        assertThat(trace.value).isEqualTo("request-var");
        assertThat(trace.source).isEqualTo("request variables");
        assertThat(trace.scope).isEqualTo("request");
        assertThat(trace.shadowedSource).isEqualTo("runtime overlay");
        assertThat(trace.shadowedValue).isEqualTo("overlay-var");
        assertThat(trace.activeEnvironmentName).isEqualTo("UAT");
        assertThat(trace.candidates)
                .extracting(candidate -> candidate.source + "=" + candidate.value)
                .containsExactly(
                        "collection environment=collection-env",
                        "collection variables=collection-var",
                        "folder variables=folder-var",
                        "collection runtime OAuth2=runtime-oauth2",
                        "collection runtime vars=runtime-var",
                        "active environment=active-env",
                        "runtime overlay=overlay-var",
                        "request variables=request-var"
                );
        assertThat(trace.message)
                .contains("resolved from request variables")
                .contains("Shadowed runtime overlay value exists");
    }

    @Test
    void inspectTreatsBlankWinningValueAsUnresolvedWhenShadowingDefinedValue() {
        ApiCollection collection = new ApiCollection();
        collection.variables.add(variable("token", "collection-token"));

        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Blank";
        activeEnvironment.variables.put("token", "");

        RuntimeResolverFactory.ResolutionTrace trace = RuntimeResolverFactory.inspect(
                collection,
                new ApiRequest(),
                activeEnvironment,
                Map.of(),
                "token"
        );

        assertThat(trace.resolved).isFalse();
        assertThat(trace.value).isEmpty();
        assertThat(trace.source).isEqualTo("active environment");
        assertThat(trace.shadowedSource).isEqualTo("collection variables");
        assertThat(trace.shadowedValue).isEqualTo("collection-token");
        assertThat(trace.message)
                .contains("empty value")
                .contains("Shadowed collection variables value exists");
    }

    @Test
    void buildCanExcludeCollectionRuntimeLayersForExportStyleIsolation() {
        ApiCollection collection = new ApiCollection();
        collection.variables.add(variable("base_url", "https://api.example.test"));
        collection.runtimeVars.put("runtime_only", "secret");

        ApiRequest request = new ApiRequest();
        request.url = "{{base_url}}/{{runtime_only}}";

        var resolver = RuntimeResolverFactory.build(
                collection,
                request,
                RuntimeResolverFactory.Options.withRuntimeVariableOverlay(Map.of())
                        .withCollectionRuntimeLayers(false)
        );

        assertThat(resolver.resolve(request.url)).isEqualTo("https://api.example.test/{{runtime_only}}");
        assertThat(resolver.findUnresolvedVariables(request.url)).containsExactly("runtime_only");
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
