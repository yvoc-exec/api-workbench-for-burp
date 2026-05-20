package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class VariableResolverTest {

    private VariableResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VariableResolver();
    }

    @Test
    void resolvesSimpleVariable() {
        resolver.addCustomVariable("base_url", "https://api.example.com");
        assertThat(resolver.resolve("{{base_url}}/users")).isEqualTo("https://api.example.com/users");
    }

    @Test
    void resolvesVariableWithDefaultWhenMissing() {
        assertThat(resolver.resolve("{{api_key|default_key}}")).isEqualTo("default_key");
    }

    @Test
    void keepsUnresolvedTokensWhenNoValueAndNoDefault() {
        assertThat(resolver.resolve("{{missing}}")).isEqualTo("{{missing}}");
    }

    @Test
    void resolvesNestedVariables() {
        resolver.addCustomVariable("host", "example.com");
        resolver.addCustomVariable("url", "https://{{host}}/api");
        assertThat(resolver.resolve("{{url}}/users")).isEqualTo("https://example.com/api/users");
    }

    @Test
    void collectionEnvironmentVariablesAreAdded() {
        ApiCollection col = new ApiCollection();
        col.environment.put("env_key", "env_value");
        resolver.addEnvironmentVariables(col);
        assertThat(resolver.resolve("{{env_key}}")).isEqualTo("env_value");
    }

    @Test
    void collectionVariablesAreAdded() {
        ApiCollection col = new ApiCollection();
        col.variables.add(createVar("col_key", "col_value"));
        resolver.addCollectionVariables(col);
        assertThat(resolver.resolve("{{col_key}}")).isEqualTo("col_value");
    }

    @Test
    void folderVariablesApplyParentThenChildScope() {
        ApiCollection col = new ApiCollection();
        col.folderVars.put("Admin", new java.util.LinkedHashMap<>(java.util.Map.of("token", "parent", "role", "admin")));
        col.folderVars.put("Admin/Nested", new java.util.LinkedHashMap<>(java.util.Map.of("token", "child")));

        ApiRequest req = new ApiRequest();
        req.name = "Get Detail";
        req.path = "Admin/Nested/Get Detail";

        resolver.addFolderVariables(col, req);

        assertThat(resolver.resolve("{{token}}")).isEqualTo("child");
        assertThat(resolver.resolve("{{role}}")).isEqualTo("admin");
    }

    @Test
    void folderVariablesNoOpWhenRequestHasNoFolderPath() {
        ApiCollection col = new ApiCollection();
        col.folderVars.put("Admin", new java.util.LinkedHashMap<>(java.util.Map.of("token", "parent")));

        ApiRequest req = new ApiRequest();
        req.name = "Root";
        req.path = "Root";

        resolver.addFolderVariables(col, req);

        assertThat(resolver.resolve("{{token}}")).isEqualTo("{{token}}");
    }

    @Test
    void requestVariablesAreAdded() {
        ApiRequest req = new ApiRequest();
        req.variables.add(createVar("req_key", "req_value"));
        resolver.addRequestVariables(req);
        assertThat(resolver.resolve("{{req_key}}")).isEqualTo("req_value");
    }

    @Test
    void laterAdditionsOverrideEarlierOnes() {
        resolver.addCustomVariable("key", "first");
        resolver.addCustomVariable("key", "second");
        assertThat(resolver.resolve("{{key}}")).isEqualTo("second");
    }

    @Test
    void clearRemovesAllVariables() {
        resolver.addCustomVariable("key", "value");
        resolver.clear();
        assertThat(resolver.resolve("{{key}}")).isEqualTo("{{key}}");
    }

    @Test
    void findUnresolvedVariablesDetectsMissingKeys() {
        resolver.addCustomVariable("present", "yes");
        Set<String> missing = resolver.findUnresolvedVariables("{{present}} and {{missing}}");
        assertThat(missing).containsExactly("missing");
    }

    @Test
    void findUnresolvedVariablesReturnsEmptyWhenAllResolved() {
        resolver.addCustomVariable("a", "1");
        resolver.addCustomVariable("b", "2");
        Set<String> missing = resolver.findUnresolvedVariables("{{a}} and {{b}}");
        assertThat(missing).isEmpty();
    }

    @Test
    void findUnresolvedVariablesIgnoresVariablesWithDefaults() {
        Set<String> missing = resolver.findUnresolvedVariables("{{base_url|https://example.com}}/api/{{missing}}");

        assertThat(missing).containsExactly("missing");
    }

    @Test
    void getVariablesReturnsDefensiveCopy() {
        resolver.addCustomVariable("key", "value");
        resolver.getVariables().put("key", "tampered");
        assertThat(resolver.resolve("{{key}}")).isEqualTo("value");
    }

    @Test
    void mutableVariablesReturnsLiveMap() {
        resolver.addCustomVariable("key", "value");
        resolver.mutableVariables().remove("key");
        assertThat(resolver.resolve("{{key}}")).isEqualTo("{{key}}");
    }

    private ApiRequest.Variable createVar(String key, String value) {
        ApiRequest.Variable v = new ApiRequest.Variable();
        v.key = key;
        v.value = value;
        return v;
    }
}
