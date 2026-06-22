package burp.smoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRuntimeProbeTest {

    @Test
    void runProbeEvaluatesSimpleJavaScriptAndReportsEngineDetails() {
        ScriptRuntimeProbe.ProbeResult result = ScriptRuntimeProbe.runProbe(true);

        assertThat(result).isNotNull();
        assertThat(result.exitCode).isZero();
        assertThat(result.success).isTrue();
        assertThat(result.engineName).isEqualTo("GraalJS");
        assertThat(result.evaluationResult).isNotNull();
        assertThat(String.valueOf(result.evaluationResult)).isEqualTo("2");
        assertThat(result.initializationFailure).isNull();
        assertThat(result.graalAvailable || result.nashornFallbackAvailable).isTrue();
    }
}
