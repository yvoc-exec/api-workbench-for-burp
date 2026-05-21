package burp.utils;

import burp.models.RunnerResult;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ScriptEnginePostmanApiTest {

    @Test
    void postmanExpectSupportsStatusHeaderPropertyAndEqualChains() {
        VariableResolver resolver = new VariableResolver();
        Map<String, String> context = new HashMap<>();
        RunnerResult result = new RunnerResult();
        result.responseBody = "{\"token\":\"abc\",\"nested\":{\"id\":7}}";

        Map<String, List<String>> headers = new HashMap<>();
        headers.put("content-type", List.of("application/json"));
        Map<String, Object> jsonData = new HashMap<>();
        jsonData.put("token", "abc");

        ScriptEngine.PostmanApi api = new ScriptEngine.PostmanApi(
                resolver, context, null, result, 200, headers, jsonData);

        api.expect(api.response.code()).to.have.status(200);
        api.expect(api.response).to.have.header("Content-Type");
        api.expect(jsonData).to.have.property("token");
        api.expect("abc").to.equal("abc");
        api.expect("abc").to.eql("abc");

        assertThat(result.assertions)
                .extracting(a -> a.passed)
                .containsOnly(true);
    }

    @Test
    void postmanTestCapturesThrownAssertionAsFailure() {
        VariableResolver resolver = new VariableResolver();
        RunnerResult result = new RunnerResult();
        ScriptEngine.PostmanApi api = new ScriptEngine.PostmanApi(
                resolver, new HashMap<>(), null, result, 404, new HashMap<>(), new HashMap<>());

        api.test("status should be 200", () -> api.expect(api.response.code()).to.have.status(200));

        assertThat(result.assertions).hasSize(1);
        assertThat(result.assertions.get(0).name).isEqualTo("status should be 200");
        assertThat(result.assertions.get(0).passed).isFalse();
    }

    @Test
    void collectionVariablesGetFallsBackToResolverVariables() {
        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("collection_token", "from-collection");
        Map<String, String> context = new HashMap<>();

        ScriptEngine.PostmanApi api = new ScriptEngine.PostmanApi(resolver, context, null);

        assertThat(api.collectionVariables.get("collection_token")).isEqualTo("from-collection");
    }

    @Test
    void environmentGetPrefersRuntimeContextThenResolverVariables() {
        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("base_url", "https://resolver.example");
        Map<String, String> context = new HashMap<>();
        context.put("base_url", "https://runtime.example");

        ScriptEngine.PostmanApi api = new ScriptEngine.PostmanApi(resolver, context, null);

        assertThat(api.environment.get("base_url")).isEqualTo("https://runtime.example");
        assertThat(api.environment.get("missing_from_context")).isEqualTo("");
    }

    @Test
    void collectionVariablesUnsetRemovesContextResolverAndExtractedVars() {
        VariableResolver resolver = new VariableResolver();
        Map<String, String> context = new HashMap<>();
        ScriptEngine.PostmanApi api = new ScriptEngine.PostmanApi(resolver, context, null);

        api.collectionVariables.set("token", "abc123");
        api.collectionVariables.unset("token");

        assertThat(context).doesNotContainKey("token");
        assertThat(resolver.mutableVariables()).doesNotContainKey("token");
        assertThat(api.getExtractedVars()).doesNotContainKey("token");
    }
}
