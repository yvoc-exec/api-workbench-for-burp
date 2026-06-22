package burp.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ScriptModeDetectorTest {

    @Test
    void parseJavaMajorVersion_runtimeVersion() {
        // Runtime.version() is available on Java 9+; we just verify it doesn't throw
        int version = ScriptModeDetector.parseJavaMajorVersion();
        assertThat(version).isGreaterThanOrEqualTo(8);
    }

    @Test
    void detect_doesNotThrow() {
        ScriptModeDetector.DetectionResult result = ScriptModeDetector.detect();
        assertThat(result).isNotNull();
        assertThat(result.mode).isNotNull();
        assertThat(result.javaVersion).isGreaterThanOrEqualTo(8);
        assertThat(result.engineName).isNotNull();
    }

    @Test
    void probeJavaScriptRuntime_returnsNullOrDetailedReason() {
        String reason = ScriptModeDetector.probeJavaScriptRuntime();
        if (reason != null) {
            assertThat(reason).isNotBlank();
        }
    }
}
