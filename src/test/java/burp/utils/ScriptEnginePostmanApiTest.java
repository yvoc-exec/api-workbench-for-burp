package burp.utils;

import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ScriptEnginePostmanApiTest {

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
