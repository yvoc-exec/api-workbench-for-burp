package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelOAuth2PopulateTest {

    @Test
    void buildOAuth2PopulateResolverUsesCollectionAndRequestScope() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.environment.put("base_url", "https://env.example.test");
        collection.variables.add(variable("collection_only", "from-collection"));
        collection.runtimeOAuth2.put("oauth2_access_token", "existing-token");
        collection.runtimeVars.put("client_id", "runtime-client");

        ApiRequest request = new ApiRequest();
        request.variables.add(variable("request_only", "from-request"));

        VariableResolver resolver = ImporterPanel.buildOAuth2PopulateResolver(collection, request);

        assertThat(resolver.resolve("{{base_url}}")).isEqualTo("https://env.example.test");
        assertThat(resolver.resolve("{{collection_only}}")).isEqualTo("from-collection");
        assertThat(resolver.resolve("{{oauth2_access_token}}")).isEqualTo("existing-token");
        assertThat(resolver.resolve("{{client_id}}")).isEqualTo("runtime-client");
        assertThat(resolver.resolve("{{request_only}}")).isEqualTo("from-request");
    }

    @Test
    void collectUnresolvedOAuth2PopulateVariablesReturnsVariableNamesFromResolvedFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("oauth2_client_id", "client-123");
        fields.put("oauth2_client_secret", "{{missing_secret}}");
        fields.put("oauth2_token_url", "{{auth_base_url}}/oauth2/token");

        List<String> unresolved = ImporterPanel.collectUnresolvedOAuth2PopulateVariables(fields);

        assertThat(unresolved).containsExactly("auth_base_url", "missing_secret");
    }

    @Test
    void buildOAuth2PopulateExistingVarsUsesCollectionRuntimeValuesLast() {
        ApiCollection collection = new ApiCollection();
        collection.environment.put("client_id", "env-client");
        collection.variables.add(variable("scope", "read"));
        collection.runtimeOAuth2.put("oauth2_token_url", "https://auth.example.test/token");
        collection.runtimeVars.put("client_id", "runtime-client");

        Map<String, String> existing = ImporterPanel.buildOAuth2PopulateExistingVars(collection);

        assertThat(existing)
                .containsEntry("client_id", "runtime-client")
                .containsEntry("scope", "read")
                .containsEntry("oauth2_token_url", "https://auth.example.test/token");
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }
}
