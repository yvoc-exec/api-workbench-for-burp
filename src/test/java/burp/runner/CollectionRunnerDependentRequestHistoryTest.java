package burp.runner;

import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.history.HistoryEntry;
import burp.history.HistoryResult;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerDependentRequestHistoryTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        DiagnosticStore diagnostics = DiagnosticStore.getInstance();
        diagnostics.setCaptureEnabled(false);
        diagnostics.clear();
    }

    @Test
    void dependentChildHistoryPreservesRawRequestScriptOutputAndHistoryDiagnostics() {
        DiagnosticStore diagnostics = DiagnosticStore.getInstance();
        diagnostics.setCaptureEnabled(true);
        diagnostics.clear();

        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(201, "{\"ok\":true}", "application/json")));
        RunnerScriptTestFixtures.RecordingRunnerListener listener =
                new RunnerScriptTestFixtures
                        .RecordingRunnerListener();
        runner.addListener(listener);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());
        runner.setRuntimeVariableSink((collection, changedVars, removedKeys) -> {
            if (changedVars != null) {
                environment.variables.putAll(changedVars);
            }
            if (removedKeys != null) {
                removedKeys.forEach(environment.variables::remove);
            }
        });

        ApiRequest child = RunnerScriptTestFixtures.request(
                "history-child",
                "History Child",
                2,
                "History Collection",
                "{{base_url}}/history-child",
                """
                        console.log('history child pre');
                        pm.environment.set('history_token', 'child-value');
                        pm.request.headers.upsert('Authorization', 'Bearer ' + pm.environment.get('history_token'));
                        pm.request.url = pm.request.url + '?history=1';
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        child.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(
                "history-child-post",
                ScriptDialect.POSTMAN,
                ScriptPhase.POST_RESPONSE,
                ScriptScope.REQUEST,
                """
                        console.log('history child post');
                        pm.test('child status', function () {
                            pm.expect(pm.response.code).to.equal(201);
                        });
                        """,
                3));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "history-parent",
                "History Parent",
                1,
                "History Collection",
                "{{base_url}}/history-parent",
                """
                        pm.execution.runRequest('History Child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("History Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        RunnerResult publishedChildAttempt =
                listener.attemptResults.stream()
                        .filter(
                                attempt ->
                                        attempt != null
                                                && "history-child"
                                                        .equals(
                                                                attempt.requestId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Dependent child attempt was not published"));

        assertThat(publishedChildAttempt.dependentExecution).isTrue();
        assertThat(publishedChildAttempt.triggeredByScript).isTrue();
        assertThat(publishedChildAttempt.parentRequestName).isEqualTo("History Parent");
        assertThat(publishedChildAttempt.parentRequestId).isEqualTo("history-parent");
        assertThat(publishedChildAttempt.dependentDepth).isEqualTo(1);
        assertThat(publishedChildAttempt.targetResolutionForm).isEqualTo(FlowTargetResolutionForm.UNIQUE_NAME);
        assertThat(publishedChildAttempt.qualifiedTargetPath).isEqualTo("History Collection/History Child");

        HistoryEntry childHistory = HistoryEntry.fromRunnerAttempt(collection, child, environment, publishedChildAttempt);

        assertThat(childHistory.source.name()).isEqualTo("RUNNER");
        assertThat(childHistory.result).isEqualTo(HistoryResult.SUCCESS);
        assertThat(childHistory.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(childHistory.requestSnapshot.rawRequestSentText).contains("Authorization: Bearer child-value");
        assertThat(childHistory.requestSnapshot.rawRequestSentText).contains("?history=1");
        assertThat(childHistory.requestSnapshot.authoredRequest).isNotNull();
        assertThat(childHistory.requestSnapshot.resolvedUrl).isEqualTo("https://api.example.test/history-child?history=1");
        assertThat(childHistory.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("history child pre"));
        assertThat(childHistory.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("history child post"));
        assertThat(childHistory.scriptWarnings).isEmpty();
        assertThat(childHistory.scriptErrors).isEmpty();
        assertThat(childHistory.scriptVariableMutations).isNotEmpty();
        assertThat(childHistory.executionSource).isEqualTo("RUNNER");
        assertThat(childHistory.dependentExecution).isTrue();
        assertThat(childHistory.parentRequestName).isEqualTo("History Parent");
        assertThat(childHistory.parentRequestId).isEqualTo("history-parent");
        assertThat(childHistory.dependentDepth).isEqualTo(1);
        assertThat(childHistory.targetResolutionForm).isEqualTo(FlowTargetResolutionForm.UNIQUE_NAME.name());
        assertThat(childHistory.qualifiedTargetPath).isEqualTo("History Collection/History Child");

        assertThat(diagnostics.snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.HISTORY_CAPTURE);
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.INFO);
                    assertThat(event.message).isEqualTo("Runner history captured");
                    assertThat(event.details).contains("rawRequestAvailable=true");
                });

    }
}
