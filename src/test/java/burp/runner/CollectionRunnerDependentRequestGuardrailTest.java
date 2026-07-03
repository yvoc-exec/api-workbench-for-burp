package burp.runner;

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

class CollectionRunnerDependentRequestGuardrailTest {

    @Test
    void directSelfRecursionIsBlockedBeforeSecondSend() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "self-parent",
                "Parent",
                1,
                "Guardrail Collection",
                "https://api.example.test/parent",
                """
                        pm.execution.runRequest('Parent');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Guardrail Collection", parent);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        RunnerResult parentResult = runner.getResults().get(0);
        assertThat(parentResult.success).isTrue();
        assertThat(parentResult.scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("Dependent request recursion detected"));
        assertThat(parentResult.scriptDependentRequestResults).isEmpty();
        assertThat(parentResult.dependentRequestCount).isZero();
    }

    @Test
    void mutualRecursionIsBlockedWithoutInfiniteLoop() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "mutual-parent",
                "Parent",
                1,
                "Guardrail Collection",
                "https://api.example.test/parent",
                """
                        pm.execution.runRequest('Child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiRequest child = RunnerScriptTestFixtures.request(
                "mutual-child",
                "Child",
                2,
                "Guardrail Collection",
                "https://api.example.test/child",
                """
                        pm.execution.runRequest('Parent');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Guardrail Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(3);
        assertThat(runner.getResults()).hasSize(3);
        RunnerResult childResult = runner.getResults().get(0);
        RunnerResult parentResult = runner.getResults().get(2);
        assertThat(childResult.scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("Dependent request recursion detected"));
        assertThat(childResult.success).isTrue();
        assertThat(parentResult.dependentRequestCount).isEqualTo(1);
        assertThat(parentResult.scriptDependentRequestResults).hasSize(1);
    }

    @Test
    void depthLimitBlocksDeepDependentChain() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        ApiRequest requestA = RunnerScriptTestFixtures.request(
                "depth-a",
                "A",
                1,
                "Depth Collection",
                "https://api.example.test/a",
                "pm.execution.runRequest('B');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiRequest requestB = RunnerScriptTestFixtures.request(
                "depth-b",
                "B",
                2,
                "Depth Collection",
                "https://api.example.test/b",
                "pm.execution.runRequest('C');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiRequest requestC = RunnerScriptTestFixtures.request(
                "depth-c",
                "C",
                3,
                "Depth Collection",
                "https://api.example.test/c",
                "pm.execution.runRequest('D');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiRequest requestD = RunnerScriptTestFixtures.request(
                "depth-d",
                "D",
                4,
                "Depth Collection",
                "https://api.example.test/d",
                "pm.execution.runRequest('E');",
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        ApiRequest requestE = RunnerScriptTestFixtures.request(
                "depth-e",
                "E",
                5,
                "Depth Collection",
                "https://api.example.test/e",
                null,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Depth Collection", requestA, requestB, requestC, requestD, requestE);

        runner.runCollections(List.of(collection), List.of(requestA));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(4);
        assertThat(runner.getResults()).hasSize(4);
        assertThat(runner.getResults().get(0).requestName).isEqualTo("D");
        assertThat(runner.getResults().get(0).scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("Dependent request depth limit reached"));
        assertThat(runner.getResults().get(3).requestName).isEqualTo("A");
        assertThat(runner.getResults().get(3).dependentRequestCount).isEqualTo(1);
    }

    @Test
    void disabledTargetIsRejectedBeforeSend() {
        AtomicInteger sendCount = new AtomicInteger();
        CollectionRunner runner = RunnerScriptTestFixtures.newRunner(
                RunnerScriptTestFixtures.mockRunnerApi(sendCount, null, () -> RunnerScriptTestFixtures.mockResponse(200, "OK", "text/plain")));

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.name = "Dev";
        runner.setRuntimeOverlayProvider(collection -> environment.toRuntimeOverlay());

        ApiRequest child = RunnerScriptTestFixtures.request(
                "disabled-child",
                "Disabled Child",
                2,
                "Disabled Collection",
                "https://api.example.test/child",
                null,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);
        child.disabled = true;

        ApiRequest parent = RunnerScriptTestFixtures.request(
                "disabled-parent",
                "Parent",
                1,
                "Disabled Collection",
                "https://api.example.test/parent",
                """
                        pm.execution.runRequest('Disabled Child');
                        """,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                ScriptScope.REQUEST);

        ApiCollection collection = RunnerScriptTestFixtures.collection("Disabled Collection", parent, child);

        runner.runCollections(List.of(collection), List.of(parent));
        RunnerScriptTestFixtures.waitForRunnerToStop(runner);

        assertThat(sendCount.get()).isEqualTo(1);
        assertThat(runner.getResults()).hasSize(1);
        RunnerResult parentResult = runner.getResults().get(0);
        assertThat(parentResult.success).isTrue();
        assertThat(parentResult.scriptWarnings).anySatisfy(warning -> assertThat(warning).contains("Flow target is disabled"));
        assertThat(parentResult.scriptDependentRequestResults).isEmpty();
        assertThat(parentResult.dependentRequestCount).isZero();
    }
}
