package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelEnvBindingTest {

    @Test
    void applyEnvVarsToRequestVariablesUpsertsAndEnablesValues() {
        ApiRequest request = new ApiRequest();
        ApiRequest.Variable existing = new ApiRequest.Variable();
        existing.key = "base_url";
        existing.value = "https://old.example.test";
        existing.enabled = false;
        request.variables.add(existing);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("base_url", "https://uat.example.test");
        env.put("client_id", "uat-client");

        int changed = ImporterPanel.applyEnvVarsToRequestVariables(request, env);

        assertThat(changed).isEqualTo(2);
        assertThat(request.variables).hasSize(2);
        assertThat(request.variables)
                .anySatisfy(v -> {
                    assertThat(v.key).isEqualTo("base_url");
                    assertThat(v.value).isEqualTo("https://uat.example.test");
                    assertThat(v.enabled).isTrue();
                })
                .anySatisfy(v -> {
                    assertThat(v.key).isEqualTo("client_id");
                    assertThat(v.value).isEqualTo("uat-client");
                    assertThat(v.enabled).isTrue();
                });
    }

    @Test
    void applyEnvVarsToRequestVariablesSkipsNullKeys() {
        ApiRequest request = new ApiRequest();
        Map<String, String> env = new LinkedHashMap<>();
        env.put(null, "ignored");
        env.put("token", "abc");

        int changed = ImporterPanel.applyEnvVarsToRequestVariables(request, env);

        assertThat(changed).isEqualTo(1);
        assertThat(request.variables).extracting(v -> v.key).containsExactly("token");
    }

    @Test
    void requestLevelEnvBindingOverridesCollectionRuntimeVars() {
        ApiCollection collection = new ApiCollection();
        collection.runtimeVars.put("base_url", "https://collection.example.test");

        ApiRequest request = new ApiRequest();
        request.name = "Get Users";

        Map<String, String> env = new LinkedHashMap<>();
        env.put("base_url", "https://uat.example.test");
        ImporterPanel.applyEnvVarsToRequestVariables(request, env);

        VariableResolver resolver = new VariableResolver();
        resolver.addCollectionVariables(collection);
        resolver.addAll(collection.runtimeVars);
        resolver.addRequestVariables(request);

        assertThat(resolver.resolve("{{base_url}}")).isEqualTo("https://uat.example.test");
    }
}
