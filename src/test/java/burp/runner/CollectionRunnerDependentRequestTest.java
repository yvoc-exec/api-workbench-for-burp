package burp.runner;

import burp.history.HistoryEntry;
import burp.history.HistoryResult;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.models.RunnerTimelineRow;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptFlowControl;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.testsupport.RunnerScriptTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRunnerDependentRequestTest {

    @Test
    void postmanRunRequestByNameExecutesDependentChildrenAndCapturesFullChildPipeline() {
        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> capturedRequests = new CopyOnWriteArrayList<>();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, capturedRequests, () -> RunnerScriptTestFixtures.mockResponse(201, "{\"ok\":true}", "application/json")));
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());
        runner.setActiveEnvironmentProvider(collection -> environment);
        runner.setRuntimeVariableSink((collection, changedVars, removedKeys) -> {
            if (changedVars != null) {
                environment.variables.putAll(changedVars);
            }
            if (removedKeys != null) {
                removedKeys.forEach(environment.variables::remove);
            }
        });

        ApiRequest child = RunnerScriptTestFixtures.request(
                "child-1",
                "Child",
                2,
                "Dependent Collection",
                "{{base_url}}/child",
                """
                        console.log('child pre');
                        pm.request.headers.upsert('Authorization', 'Bearer ' + pm.environment.get('token'));
                        pm.request.url = pm.request.url + '?child=1';
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        child.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(
                "child-1-post",
                ScriptDialect.POSTMAN,
                ScriptPhase.POST_RESPONSE,
                ScriptScope.REQUEST,
                """
                        console.log('child post');
                        pm.test('child status', function () {
                            pm.expect(pm.response.code).to.equal(201);
                        });
                        pm.environment.set('child_seen', 'yes');
                        """,
                3));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent-1",
                "Parent",
                1,
                "Dependent Collection",
                "{{base_url}}/parent",
                """
                        console.log('parent pre');
                        pm.environment.set('token', 'runner-token');
                        pm.execution.runRequest('Child');
                        pm.execution.runRequest('Child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Dependent Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(3);
        assertThat(runner.getResults()).hasSize(3);
        assertThat(listener.timelineRows).hasSize(3);

        RunnerResult firstChild = runner.getResults().get(0);
        RunnerResult secondChild = runner.getResults().get(1);
        RunnerResult parentResult = runner.getResults().get(2);

        assertThat(List.of(firstChild.requestName, secondChild.requestName, parentResult.requestName))
                .containsExactly("Child", "Child", "Parent");
        assertThat(firstChild.dependentExecution).isTrue();
        assertThat(secondChild.dependentExecution).isTrue();
        assertThat(firstChild.triggeredByScript).isTrue();
        assertThat(secondChild.triggeredByScript).isTrue();
        assertThat(firstChild.parentRequestName).isEqualTo("Parent");
        assertThat(secondChild.parentRequestName).isEqualTo("Parent");
        assertThat(firstChild.parentRequestId).isEqualTo("parent-1");
        assertThat(secondChild.parentRequestId).isEqualTo("parent-1");
        assertThat(firstChild.dependentDepth).isEqualTo(1);
        assertThat(secondChild.dependentDepth).isEqualTo(1);
        assertThat(parentResult.dependentRequestCount).isEqualTo(2);
        assertThat(parentResult.scriptDependentRequestResults).hasSize(2);
        assertThat(parentResult.scriptFlowControl).isEqualTo(ScriptFlowControl.RUN_REQUEST);
        assertThat(parentResult.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("parent pre"));

        assertThat(firstChild.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("child pre"));
        assertThat(firstChild.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("child post"));
        assertThat(firstChild.assertions).hasSize(1);
        assertThat(firstChild.assertions.get(0).passed).isTrue();
        assertThat(firstChild.rawRequestText).contains("Authorization: Bearer runner-token");
        assertThat(firstChild.rawRequestText).contains("?child=1");
        assertThat(firstChild.rawRequestText).contains("HTTP/1.1");
        assertThat(firstChild.requestUrl).isEqualTo("https://api.example.test/child?child=1");
        assertThat(firstChild.displayStatusLabel()).isEqualTo("201 (dependent)");

        assertThat(secondChild.rawRequestText).contains("Authorization: Bearer runner-token");
        assertThat(secondChild.requestUrl).isEqualTo("https://api.example.test/child?child=1");
        assertThat(secondChild.displayStatusLabel()).isEqualTo("201 (dependent)");

        HistoryEntry firstChildHistory = HistoryEntry.fromRunnerAttempt(collection, child, environment, firstChild);
        assertThat(firstChildHistory.result).isEqualTo(HistoryResult.SUCCESS);
        assertThat(firstChildHistory.requestSnapshot.rawRequestSentText).contains("Authorization: Bearer runner-token");
        assertThat(firstChildHistory.requestSnapshot.rawRequestSentText).contains("?child=1");
        assertThat(firstChildHistory.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("child pre"));
        assertThat(firstChildHistory.scriptWarnings).isEmpty();
        assertThat(firstChildHistory.scriptErrors).isEmpty();
    }

    @Test
    void postmanRunRequestByIdSelectsExactDuplicateNamedTarget() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
        runner.addListener(listener);

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());

        ApiRequest targetOne = RunnerScriptTestFixtures.request(
                "child-one",
                "Child",
                2,
                "Duplicate Collection",
                "{{base_url}}/child-one",
                null,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiRequest targetTwo = RunnerScriptTestFixtures.request(
                "child-two",
                "Child",
                3,
                "Duplicate Collection",
                "{{base_url}}/child-two",
                """
                        pm.request.headers.upsert('X-Target', 'second');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "parent-dup",
                "Parent",
                1,
                "Duplicate Collection",
                "{{base_url}}/parent",
                """
                        pm.execution.runRequest('child-two');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Duplicate Collection", parent, targetOne, targetTwo);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        assertThat(listener.timelineRows).hasSize(2);
        assertThat(runner.getResults().get(0).requestId).isEqualTo("child-two");
        assertThat(runner.getResults().get(0).rawRequestText).contains("X-Target: second");
        assertThat(runner.getResults().get(0).requestUrl).isEqualTo("https://api.example.test/child-two");
        assertThat(runner.getResults().get(0).parentRequestId).isEqualTo("parent-dup");
        assertThat(runner.getResults().get(1).requestId).isEqualTo("parent-dup");
        assertThat(runner.getResults().get(1).dependentRequestCount).isEqualTo(1);
        assertThat(runner.getResults().get(1).scriptDependentRequestResults).hasSize(1);
        assertThat(listener.timelineRows.get(0).status).isEqualTo("200 (dependent)");
        assertThat(listener.timelineRows.get(1).status).isEqualTo("200");
    }

    @Test
    void brunoRunRequestExecutesTargetAndRetainsLogsAssertionsAndRawRequest() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(201, "{\"ok\":true}", "application/json")));
        RunnerScriptTestFixtures.RecordingRunnerListener listener = new RunnerScriptTestFixtures.RecordingRunnerListener();
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
                "bruno-child",
                "Bruno Child",
                2,
                "Bruno Collection",
                "{{base_url}}/bruno-child",
                """
                        bru.setEnvVar('bruno_token', 'bruno-runner');
                        bru.req.headers.upsert('Authorization', 'Bearer ' + bru.getEnvVar('bruno_token'));
                        bru.req.url = bru.req.url + '?dialect=bruno';
                        console.log('bruno child pre');
                        """,
                ScriptDialect.BRUNO,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        child.scriptBlocks.add(RunnerScriptTestFixtures.scriptBlock(
                "bruno-child-post",
                ScriptDialect.BRUNO,
                ScriptPhase.POST_RESPONSE,
                ScriptScope.REQUEST,
                """
                        bru.test('child status', function () {
                            bru.expect(bru.res.code).to.equal(201);
                        });
                        console.log('bruno child post');
                        """,
                3));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "bruno-parent",
                "Bruno Parent",
                1,
                "Bruno Collection",
                "{{base_url}}/bruno-parent",
                """
                        bru.runRequest('Bruno Child');
                        """,
                ScriptDialect.BRUNO,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Bruno Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        RunnerResult childResult = runner.getResults().get(0);
        RunnerResult parentResult = runner.getResults().get(1);
        assertThat(childResult.requestId).isEqualTo("bruno-child");
        assertThat(childResult.dependentExecution).isTrue();
        assertThat(childResult.adHocExecution).isFalse();
        assertThat(childResult.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("bruno child pre"));
        assertThat(childResult.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("bruno child post"));
        assertThat(childResult.assertions).hasSize(1);
        assertThat(childResult.rawRequestText).contains("Authorization: Bearer bruno-runner");
        assertThat(childResult.rawRequestText).contains("dialect=bruno");
        assertThat(childResult.displayStatusLabel()).isEqualTo("201 (dependent)");
        assertThat(parentResult.scriptDependentRequestResults).hasSize(1);
        assertThat(parentResult.dependentRequestCount).isEqualTo(1);
        assertThat(childResult.resolvedVariables).containsEntry("bruno_token", "bruno-runner");
        assertThat(environment.variables).doesNotContainKey("bruno_token");
        assertThat(listener.timelineRows).extracting(row -> row.status).contains("201 (dependent)", "201");
    }

    @Test
    void nativeRunRequestExecutesTargetAndCapturesDependentMetadata() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json")));

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        environment.variables.put("base_url", "https://api.example.test");
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());

        ApiRequest child = RunnerScriptTestFixtures.request(
                "native-child",
                "Native Child",
                2,
                "Native Collection",
                "{{base_url}}/native-child",
                """
                        awb.environment.set('native_token', 'native-runner');
                        awb.request.headers.upsert('Authorization', 'Bearer ' + awb.environment.get('native_token'));
                        awb.request.method = 'PUT';
                        awb.request.url = awb.request.url + '?native=1';
                        console.log('native child pre');
                        """,
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "native-parent",
                "Native Parent",
                1,
                "Native Collection",
                "{{base_url}}/native-parent",
                """
                        awb.execution.runRequest('Native Child');
                        """,
                ScriptDialect.API_WORKBENCH,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Native Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(2);
        assertThat(runner.getResults()).hasSize(2);
        RunnerResult childResult = runner.getResults().get(0);
        RunnerResult parentResult = runner.getResults().get(1);
        assertThat(childResult.requestId).isEqualTo("native-child");
        assertThat(childResult.dependentExecution).isTrue();
        assertThat(childResult.triggeredByScript).isTrue();
        assertThat(childResult.rawRequestText).contains("Authorization: Bearer native-runner");
        assertThat(childResult.rawRequestText).contains("PUT /native-child?native=1 HTTP/1.1");
        assertThat(childResult.rawRequestText).contains("Host: api.example.test");
        assertThat(childResult.scriptLogs).anySatisfy(log -> assertThat(log.message).contains("native child pre"));
        assertThat(childResult.displayStatusLabel()).isEqualTo("200 (dependent)");
        assertThat(parentResult.scriptDependentRequestResults).hasSize(1);
        assertThat(parentResult.dependentRequestCount).isEqualTo(1);
    }
}
