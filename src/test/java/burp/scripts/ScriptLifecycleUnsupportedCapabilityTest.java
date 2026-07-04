package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptLifecycleUnsupportedCapabilityTest {
    @Test
    void unsupportedCapabilityIsReportedAndFailsClosedBeforeSandbox() throws Exception {
        GraalJsSandboxEngine engine = mock(GraalJsSandboxEngine.class);
        when(engine.getEngineName()).thenReturn("MockSandbox");
        ScriptLifecycleExecutor executor = new ScriptLifecycleExecutor(engine);

        ApiCollection collection = new ApiCollection();
        collection.name = "Collection";
        ApiRequest request = new ApiRequest();
        request.id = "request-id";
        request.name = "Request";
        request.method = "GET";
        request.url = "https://example.invalid/original";
        ScriptExecutionContext context = new ScriptExecutionContext(
                collection,
                request,
                null,
                ExecutionSource.WORKBENCH_SEND,
                1);
        context.scriptErrorsStopExecution = false;

        ScriptBlock block = ScriptBlock.of(
                "pm.sendRequest('https://example.invalid/blocked');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        block.id = "blocked-script";
        block.sourceFormat = "postman";

        UnsupportedScriptCapabilityException failure = catchThrowableOfType(
                () -> executor.execute(context, List.of(block)),
                UnsupportedScriptCapabilityException.class);
        ScriptExecutionResult result = failure.result();

        assertThat(failure).hasMessageContaining(ScriptLifecycleExecutor.UNSUPPORTED_SCRIPT_CAPABILITY)
                .hasMessageContaining("sendRequest");
        assertThat(result.success).isFalse();
        assertThat(result.hasUnsupportedCapabilities()).isTrue();
        assertThat(result.unsupportedCapabilities).singleElement().satisfies(issue -> {
            assertThat(issue.blockId()).isEqualTo("blocked-script");
            assertThat(issue.capabilityName()).isEqualTo("sendRequest");
            assertThat(issue.riskLevel()).isEqualTo(burp.scripts.capabilities.ScriptRiskLevel.CRITICAL);
        });
        assertThat(result.errors).singleElement().satisfies(error ->
                assertThat(error)
                        .contains(ScriptLifecycleExecutor.UNSUPPORTED_SCRIPT_CAPABILITY)
                        .contains("sendRequest"));
        assertThat(result.mutatedRequest.url).isEqualTo("https://example.invalid/original");
        verify(engine, never()).execute(anyString(), anyMap(), any(Runnable.class));
    }
}
