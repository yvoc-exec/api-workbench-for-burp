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

    @Test
    void dependentChildHistoryPreservesRawRequestScriptOutputAndHistoryDiagnostics() {
        DiagnosticStore.getInstance().clear();

        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(201, "{\"ok\":true}", "application/json")));

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
        RunnerResult childResult = runner.getResults().get(0);
        HistoryEntry childHistory = HistoryEntry.fromRunnerAttempt(collection, child, environment, childResult);

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

        assertThat(DiagnosticStore.getInstance().snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.HISTORY_CAPTURE);
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.INFO);
                    assertThat(event.message).isEqualTo("Runner history captured");
                    assertThat(event.details).contains("rawRequestAvailable=true");
                });

        DiagnosticStore.getInstance().clear();
    }
}
