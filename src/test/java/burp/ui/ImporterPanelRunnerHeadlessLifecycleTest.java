package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerResult;
import burp.models.RunnerTerminationResult;
import burp.models.RunnerTerminationType;
import burp.runner.CollectionRunner;
import burp.testsupport.ImporterPanelTestSupport;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.testsupport.UiWorkflowFixtures;
import burp.utils.ExecutionResult;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ImporterPanelRunnerHeadlessLifecycleTest {

    @Test
    void cancelWhilePausedReturnsRunnerAndControlsToIdle() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MontoyaApi api = UiWorkflowFixtures.mockUiApi(null);
        CollectionRunner runner = new CollectionRunner(
                api,
                successfulPipeline(api, calls, "/paused"),
                null);
        runner.setDelayMs(0);
        runner.setMaxRetries(0);
        ImporterPanel panel = panel(api, runner);

        CountDownLatch firstResult = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        installLifecycleLatch(runner, firstResult, terminal);

        ApiRequest first = request("One");
        ApiRequest second = request("Two");
        ApiCollection collection = collection("Paused", first, second);
        panel.restoreWorkspaceCollections(List.of(collection));
        panel.queueRunnerRequestsForTests(List.of(first, second));

        ImporterPanelTestSupport.invokeVoid(
                panel,
                "startRunnerExecution",
                new Class<?>[]{List.class, boolean.class},
                List.of(first, second),
                true);

        assertThat(firstResult.await(5, TimeUnit.SECONDS)).isTrue();
        ImporterPanelTestSupport.awaitCondition(
                () -> runner.isRunning()
                        && runner.isPaused()
                        && panel.getCancelRunnerButtonForTests().isEnabled()
                        && !panel.getPauseRunnerButtonForTests().isEnabled()
                        && panel.getResumeRunnerButtonForTests().isEnabled(),
                Duration.ofSeconds(5));

        panel.getCancelRunnerButtonForTests().doClick();

        assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
        awaitCancelledAndIdle(panel, runner);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(runner.getLastTerminationResult().type)
                .isEqualTo(RunnerTerminationType.CANCELLED);
    }

    @Test
    void cancelDuringDelayRetainsCompletedResultAndProgress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MontoyaApi api = UiWorkflowFixtures.mockUiApi(null);
        CollectionRunner runner = new CollectionRunner(
                api,
                successfulPipeline(api, calls, "/delay"),
                null);
        runner.setMaxRetries(0);
        ImporterPanel panel = panel(api, runner);

        CountDownLatch firstResult = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        installLifecycleLatch(runner, firstResult, terminal);

        ApiRequest first = request("One");
        ApiRequest second = request("Two");
        ApiCollection collection = collection("Delay", first, second);
        panel.restoreWorkspaceCollections(List.of(collection));
        panel.queueRunnerRequestsForTests(List.of(first, second));
        JSpinner delaySpinner = ImporterPanelTestSupport.getField(panel, "runnerDelaySpinner");
        delaySpinner.setValue(5_000);

        ImporterPanelTestSupport.invokeVoid(
                panel,
                "startRunnerExecution",
                new Class<?>[]{List.class, boolean.class},
                List.of(first, second),
                false);

        assertThat(firstResult.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.getResults()).hasSize(1);
        panel.getCancelRunnerButtonForTests().doClick();

        assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
        awaitCancelledAndIdle(panel, runner);
        RunnerTerminationResult termination = runner.getLastTerminationResult();
        assertThat(termination.completedCount).isEqualTo(1);
        assertThat(termination.totalQueuedCount).isEqualTo(2);
        assertThat(runner.getResults())
                .singleElement()
                .extracting(result -> result.requestName)
                .isEqualTo("One");
        JProgressBar progress = ImporterPanelTestSupport.getField(panel, "runnerProgress");
        assertThat(progress.getString()).contains("1/2");
    }

    private static ImporterPanel panel(MontoyaApi api, CollectionRunner runner) {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        when(importer.getApi()).thenReturn(api);
        return new ImporterPanel(
                importer,
                runner,
                Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS),
                ScriptMode.DISABLED);
    }

    private static SharedRequestPipeline successfulPipeline(
            MontoyaApi api,
            AtomicInteger calls,
            String pathPrefix) {
        return new SharedRequestPipeline(api, null, null, null) {
            @Override
            public ExecutionResult execute(
                    ApiRequest request,
                    ApiCollection collection,
                    boolean followRedirects) {
                int call = calls.incrementAndGet();
                ExecutionResult result = new ExecutionResult();
                result.success = true;
                result.response = RunnerScriptTestFixtures.mockResponse(
                        200,
                        "OK",
                        "text/plain");
                result.requestHeaders = "GET " + pathPrefix + call
                        + " HTTP/1.1\r\nHost: example.test\r\n\r\n";
                result.rawRequestBytes = result.requestHeaders.getBytes(StandardCharsets.UTF_8);
                return result;
            }
        };
    }

    private static void installLifecycleLatch(
            CollectionRunner runner,
            CountDownLatch firstResult,
            CountDownLatch terminal) {
        runner.addListener(new CollectionRunner.RunnerListener() {
            @Override
            public void onStart(String collectionName, int totalRequests) {
            }

            @Override
            public void onSkip(String requestName, String reason) {
            }

            @Override
            public void onRequestComplete(RunnerResult result) {
                firstResult.countDown();
            }

            @Override
            public void onComplete(List<RunnerResult> results) {
            }

            @Override
            public void onError(String message) {
            }

            @Override
            public void onTerminal(
                    RunnerTerminationResult termination,
                    List<RunnerResult> results) {
                terminal.countDown();
            }
        });
    }

    private static void awaitCancelledAndIdle(
            ImporterPanel panel,
            CollectionRunner runner) {
        ImporterPanelTestSupport.awaitCondition(
                () -> !runner.isRunning()
                        && runner.getLastTerminationResult() != null
                        && runner.getLastTerminationResult().type == RunnerTerminationType.CANCELLED
                        && !panel.getCancelRunnerButtonForTests().isEnabled()
                        && !panel.getPauseRunnerButtonForTests().isEnabled()
                        && !panel.getResumeRunnerButtonForTests().isEnabled(),
                Duration.ofSeconds(10));
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        for (ApiRequest request : requests) {
            request.sourceCollection = name;
            collection.requests.add(request);
        }
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.id = "req-" + name.toLowerCase();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.test/" + name.toLowerCase();
        return request;
    }
}
