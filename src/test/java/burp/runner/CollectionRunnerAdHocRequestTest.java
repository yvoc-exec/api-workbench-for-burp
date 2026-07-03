package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerAdHocRequestTest {

    @Test
    void nativeSendAdHocRequestWithObjectExecutesSafeRequestAndCapturesRawRequest() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json")));
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "ad-hoc-parent",
                "Parent",
                1,
                "Ad Hoc Collection",
                "{{base_url}}/parent",
                """
                        awb.execution.sendAdHocRequest({
                            name: 'AdHoc POST',
                            method: 'POST',
                            url: 'https://api.example.test/ad-hoc',
                            headers: [{ key: 'X-AdHoc', value: 'true' }],
                            body: '{"adHoc":true}',
                            contentType: 'application/json'
                        });
                        """,
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Ad Hoc Collection", parent);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        RunnerResult adHocResult = runner.getResults().get(0);
        RunnerResult parentResult = runner.getResults().get(1);

        assertThat(adHocResult.adHocExecution).isTrue();
        assertThat(adHocResult.dependentExecution).isTrue();
        assertThat(adHocResult.triggeredByScript).isTrue();
        assertThat(adHocResult.requestName).isEqualTo("AdHoc POST");
        assertThat(adHocResult.method).isEqualTo("POST");
        assertThat(adHocResult.requestUrl).isEqualTo("https://api.example.test/ad-hoc");
        assertThat(adHocResult.rawRequestText).contains("POST /ad-hoc HTTP/1.1");
        assertThat(adHocResult.rawRequestText).contains("Host: api.example.test");
        assertThat(adHocResult.rawRequestText).contains("X-AdHoc: true");
        assertThat(adHocResult.rawRequestText).contains("{\"adHoc\":true}");
        assertThat(adHocResult.displayStatusLabel()).isEqualTo("200 (ad hoc)");
        assertThat(parentResult.scriptFlowControl).isEqualTo(ScriptFlowControl.SEND_AD_HOC_REQUEST);
        assertThat(parentResult.scriptDependentRequestResults).hasSize(1);
        assertThat(parentResult.dependentRequestCount).isEqualTo(1);
        assertThat(listener.timelineRows).extracting(row -> row.status).contains("200", "200");
    }

    @Test
    void nativeSendAdHocRequestWithStringUrlExecutesSimpleGetRequest() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "ad-hoc-string-parent",
                "Parent",
                1,
                "Ad Hoc String Collection",
                "https://api.example.test/parent",
                """
                        awb.execution.sendAdHocRequest('https://api.example.test/ping');
                        """,
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Ad Hoc String Collection", parent);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        RunnerResult adHocResult = runner.getResults().get(0);
        assertThat(adHocResult.adHocExecution).isTrue();
        assertThat(adHocResult.method).isEqualTo("GET");
        assertThat(adHocResult.requestUrl).isEqualTo("https://api.example.test/ping");
        assertThat(adHocResult.rawRequestText).contains("GET /ping HTTP/1.1");
        assertThat(adHocResult.rawRequestText).contains("Host: api.example.test");
        assertThat(adHocResult.displayStatusLabel()).isEqualTo("200 (ad hoc)");
    }

    @Test
    void nativeSendAdHocRequestWithBlankUrlIsRejectedWithoutExecutingChild() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "ad-hoc-invalid-parent",
                "Parent",
                1,
                "Ad Hoc Invalid Collection",
                "https://api.example.test/parent",
                """
                        awb.execution.sendAdHocRequest({ method: 'POST', url: '   ' });
                        """,
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Ad Hoc Invalid Collection", parent);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        RunnerResult parentResult = runner.getResults().get(0);
        assertThat(parentResult.success).isTrue();
        assertThat(parentResult.scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("sendAdHocRequest requires a URL"));
        assertThat(parentResult.scriptDependentRequestResults).isEmpty();
        assertThat(parentResult.dependentRequestCount).isZero();
        assertThat(parentResult.scriptFlowControl).isEqualTo(ScriptFlowControl.CONTINUE);
    }
}
