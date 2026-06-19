package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.ScriptMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptSandboxTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Java.type('java.lang.System')",
            "Java.type('java.io.File')",
            "Java.type('java.lang.Thread')"
    })
    void graalSandboxBlocksHostJvmAndFileAccess(String source) {
        GraalJsSandboxEngine engine = new GraalJsSandboxEngine();

        assertThat(engine.isGraalAvailable()).isTrue();
        assertThrows(Exception.class, () -> engine.execute(source, java.util.Map.of()));
    }

    @Test
    void disabledRuntimeReturnsWarningWithoutMutatingRequest() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.id = "req-disabled";
        request.name = "Disabled";
        request.method = "GET";
        request.url = "https://api.example.test";
        request.scriptBlocks = new ArrayList<>();
        ScriptBlock block = new ScriptBlock();
        block.id = "pre";
        block.dialect = ScriptDialect.POSTMAN;
        block.phase = ScriptPhase.PRE_REQUEST;
        block.scope = ScriptScope.REQUEST;
        block.source = "pm.environment.set('token', 'abc');";
        block.enabled = true;
        request.scriptBlocks.add(block);

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.DISABLED);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

        assertThat(result.success).isTrue();
        assertThat(result.mutatedRequest).isNull();
        assertThat(result.warnings).contains("Script execution disabled or sandbox unavailable.");
        assertThat(result.engineName).isNotBlank();
    }
}
