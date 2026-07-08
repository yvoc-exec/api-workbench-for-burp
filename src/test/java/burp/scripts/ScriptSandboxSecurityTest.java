package burp.scripts;

import burp.models.ApiRequest;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.ScriptModeDetector;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSandboxSecurityTest {

    @Test
    void productionRuntimeContainsNoUnboundedLegacyExecutionPath() throws Exception {
        List<String> banned = List.of(
                "executeWith" + "Nash" + "orn",
                "Nash" + "orn" + "ScriptEngineFactory",
                "get" + "Nash" + "orn" + "Engine",
                "ScriptEngineManager",
                "getEngineByName"
        );
        String runtimeSource = Files.readString(Path.of("src/main/java/burp/scripts/SandboxedJavaScriptEngine.java"));
        String legacySource = Files.readString(Path.of("src/main/java/burp/utils/ScriptEngine.java"));

        for (String token : banned) {
            assertThat(runtimeSource).doesNotContain(token);
            assertThat(legacySource).doesNotContain(token);
        }
    }

    @Test
    void scriptSandboxStillDeniesJavaTypePackagesAndReflection() throws Exception {
        try (SandboxedJavaScriptEngine engine = new SandboxedJavaScriptEngine()) {
            assertThat(engine.isAvailable()).isTrue();
            assertThatThrownBy(() -> engine.execute("Java.type('java.lang.System').getProperty('user.home')", Map.of()))
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> engine.execute("Java.type('java.lang.Class').forName('java.lang.System')", Map.of()))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void runtimePublicIdentityDoesNotDiscloseConcreteEngineNames() {
        try (SandboxedJavaScriptEngine engine = new SandboxedJavaScriptEngine()) {
            assertThat(engine.getEngineName()).isEqualTo("Sandboxed JavaScript");
            assertThat(engine.getEngineName()).doesNotContainIgnoringCase("gr" + "aal");
            assertThat(engine.getEngineName()).doesNotContainIgnoringCase("nash" + "orn");
            assertThat(engine.getInitializationFailure() == null ? "" : engine.getInitializationFailure())
                    .doesNotContainIgnoringCase("gr" + "aal")
                    .doesNotContainIgnoringCase("nash" + "orn");
        }
    }

    @Test
    void scriptModeDetectorDoesNotAdvertiseLegacyFallback() {
        ScriptModeDetector.DetectionResult result = ScriptModeDetector.detect();
        assertThat(result.reason).doesNotContainIgnoringCase("nash" + "orn");
        assertThat(result.engineName == null ? "" : result.engineName).doesNotContainIgnoringCase("nash" + "orn");
    }

    @Test
    void legacyLimitedModeNeverEvaluatesArbitraryJavascript() {
        ScriptEngine engine = new ScriptEngine(null, ScriptMode.LIMITED);
        ApiRequest request = new ApiRequest();
        request.postResponseScripts.add(new ApiRequest.Script("js", "while (true) {}"));
        Map<String, String> context = new LinkedHashMap<>();

        engine.executePostResponse(request, null, context, new burp.models.RunnerResult(), "{\"id\":1}", 200, Map.of());

        assertThat(context).isEmpty();
    }

    @Test
    void fullJsDoesNotFallBackToLegacyUnboundedEngine() {
        ScriptEngine engine = new ScriptEngine(null, ScriptMode.FULL_JS);
        ApiRequest request = new ApiRequest();
        request.preRequestScripts.add(new ApiRequest.Script("js", "while (true) {}"));
        Map<String, String> context = new LinkedHashMap<>();
        burp.parser.VariableResolver resolver = new burp.parser.VariableResolver();

        assertThatCode(() -> engine.executePreRequest(request, resolver, context)).doesNotThrowAnyException();
        assertThat(context).isEmpty();
    }
}
