package burp.scripts.capabilities;

import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptCapabilityAnalyzerTest {
    private final ScriptCapabilityAnalyzer analyzer = new ScriptCapabilityAnalyzer();

    @Test
    void detectsUnsupportedNetworkAndHostInteropCapabilities() {
        ScriptBlock block = ScriptBlock.of(
                "pm.sendRequest('https://example.test', () => {});\n"
                        + "const processBuilder = Java.type('java.lang.ProcessBuilder');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ScriptCapabilityReport report = analyzer.analyze(block);

        assertThat(report.riskLevel).isEqualTo(ScriptRiskLevel.CRITICAL);
        assertThat(report.hasUnsupportedCapabilities()).isTrue();
        assertThat(report.capabilities()).contains(
                ScriptCapability.AD_HOC_NETWORK,
                ScriptCapability.HOST_INTEROP);
        assertThat(report.unsupportedApiNames()).contains("sendRequest", "host interop");
    }

    @Test
    void ignoresCapabilityNamesInsideCommentsAndQuotedLiterals() {
        ScriptBlock block = ScriptBlock.of(
                "// pm.sendRequest('https://ignored.test');\n"
                        + "const example = \"fetch('https://ignored.test')\";\n"
                        + "/* Java.type('java.lang.Runtime') */\n"
                        + "pm.environment.set('tenant', 'value');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ScriptCapabilityReport report = analyzer.analyze(block);

        assertThat(report.hasUnsupportedCapabilities()).isFalse();
        assertThat(report.capabilities()).containsExactly(ScriptCapability.VARIABLE_MUTATION);
        assertThat(report.riskLevel).isEqualTo(ScriptRiskLevel.MEDIUM);
    }

    @Test
    void classifiesSupportedAdHocRunnerNetworkAsCriticalRisk() {
        ScriptBlock block = ScriptBlock.of(
                "awb.execution.sendAdHocRequest({method: 'GET', url: 'https://example.test'});",
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ScriptCapabilityReport report = analyzer.analyze(block);

        assertThat(report.hasUnsupportedCapabilities()).isFalse();
        assertThat(report.riskLevel).isEqualTo(ScriptRiskLevel.CRITICAL);
        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.capability()).isEqualTo(ScriptCapability.AD_HOC_NETWORK);
            assertThat(finding.apiName()).isEqualTo("sendAdHocRequest");
            assertThat(finding.supported()).isTrue();
        });
    }

    @Test
    void detectsSensitiveVariableKeysInsideGetCallsButNotComments() {
        ScriptBlock block = ScriptBlock.of(
                "// pm.environment.get('ignored_password');\n"
                        + "const password = pm.environment.get('password');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ScriptCapabilityReport report = analyzer.analyze(block);

        assertThat(report.riskLevel).isEqualTo(ScriptRiskLevel.HIGH);
        assertThat(report.capabilities()).contains(ScriptCapability.SENSITIVE_DATA_ACCESS);
        assertThat(report.findings()).anySatisfy(finding ->
                assertThat(finding.apiName()).isEqualTo("sensitive variable access"));
    }

    @Test
    void annotatesBlockWithStableCapabilityMetadata() {
        ScriptBlock block = ScriptBlock.of(
                "pm.request.headers.upsert({key: 'X-Test', value: 'one'});",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ScriptCapabilityReport report = analyzer.analyzeAndAnnotate(block);

        assertThat(report.capabilities()).contains(ScriptCapability.REQUEST_HEADER_MUTATION);
        assertThat(block.metadata)
                .containsEntry("capabilityRisk", "MEDIUM")
                .containsEntry("unsupportedCapabilities", "");
        assertThat(block.metadata.get("capabilitySummary"))
                .contains("REQUEST_HEADER_MUTATION");
    }
}
