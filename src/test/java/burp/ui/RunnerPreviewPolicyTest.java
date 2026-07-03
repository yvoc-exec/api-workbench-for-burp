package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.RunnerPreviewRow;
import burp.models.WorkspaceState;
import burp.runner.RunnerRetryPolicy;
import burp.utils.ExecutionPolicy;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerPreviewPolicyTest {

    @Test
    void previewShowsMaxAttemptsPerMethod() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 2;
        policy.retryConnectionFailures = true;
        policy.retryTimeouts = true;
        policy.retryNonIdempotentMethods = true;
        policy.retryableMethods = new LinkedHashSet<>(List.of("GET", "POST"));
        policy.normalize();
        bundle.runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Collection",
                request("get-1", "Get", "GET", "https://example.test/get"),
                request("post-1", "Post", "POST", "https://example.test/post"));

        List<RunnerPreviewRow> rows = bundle.runner.buildRunPreview(List.of(collection), collection.requests);

        assertThat(rows).hasSize(2);
        assertThat(rowByName(rows, "Get").maximumAttempts).isEqualTo(3);
        assertThat(rowByName(rows, "Post").maximumAttempts).isEqualTo(3);
        assertThat(rowByName(rows, "Post").retryEligible).isTrue();
    }

    @Test
    void previewShowsTimeoutAndRetryEligibility() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.runner.setExecutionPolicy(ExecutionPolicy.runnerDefaults(false));
        ExecutionPolicy executionPolicy = bundle.runner.getExecutionPolicy();
        executionPolicy.responseTimeoutMillis = 1_234;
        bundle.runner.setExecutionPolicy(executionPolicy);

        RunnerRetryPolicy policy = RunnerRetryPolicy.safeDefaults();
        policy.maxRetries = 1;
        policy.retryTimeouts = true;
        policy.normalize();
        bundle.runner.setRetryPolicy(policy);

        ApiCollection collection = collection("Collection", request("get-1", "Get", "GET", "https://example.test/get"));
        List<RunnerPreviewRow> rows = bundle.runner.buildRunPreview(List.of(collection), collection.requests);

        assertThat(rows).hasSize(1);
        RunnerPreviewRow row = rows.get(0);
        assertThat(row.retryEligible).isTrue();
        assertThat(row.responseTimeoutMillis).isEqualTo(1_234);
    }

    @Test
    void previewShowsUnresolvedAuthAndTargetPolicy() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiRequest request = request("req-1", "Request", "GET", "https://example.test/{{missing}}");
        ApiCollection collection = collection("Collection", request);
        List<RunnerPreviewRow> rows = bundle.runner.buildRunPreview(List.of(collection), collection.requests);

        assertThat(rows).hasSize(1);
        RunnerPreviewRow row = rows.get(0);
        assertThat(row.unresolvedVariables).contains("missing");
        assertThat(row.authStatus).isEqualTo("No auth");
        assertThat(row.targetChangePolicy).isEqualTo("ABORT");
    }

    @Test
    void unsafeNonIdempotentRetryWarningListsRequests() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiRequest post = withAuth(request("post-1", "Create", "POST", "https://example.test/create"));
        loadCollections(bundle.panel, collection("Collection", post));
        queue(bundle.panel, post);
        applyUiRetryPolicy(bundle, 1, "POST", true, false, true, "");

        AtomicInteger warnings = new AtomicInteger();
        bundle.panel.setRunnerWarningPresenterForTests((parent, title, message) -> {
            warnings.incrementAndGet();
            assertThat(title).isEqualTo("Unsafe Runner retry policy");
            assertThat(message).contains("A timed-out sent request may already have been processed");
            assertThat(message).contains("Collection/Create [POST]");
            return false;
        });

        ImporterPanelTestSupport.invokeVoid(bundle.panel, "startRunner", new Class[]{boolean.class}, false);

        assertThat(warnings).hasValue(1);
        assertThat(bundle.runner.isRunning()).isFalse();
    }

    @Test
    void warningIsShownOnlyOnce() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiRequest post = withAuth(request("post-1", "Create", "POST", "https://example.test/create"));
        loadCollections(bundle.panel, collection("Collection", post));
        queue(bundle.panel, post);
        applyUiRetryPolicy(bundle, 1, "POST", true, false, true, "");

        AtomicInteger warnings = new AtomicInteger();
        bundle.panel.setRunnerWarningPresenterForTests((parent, title, message) -> {
            warnings.incrementAndGet();
            return false;
        });

        ImporterPanelTestSupport.invokeVoid(bundle.panel, "startRunner", new Class[]{boolean.class}, false);

        assertThat(warnings).hasValue(1);
    }

    @Test
    void rejectingWarningDoesNotStartRunner() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiRequest post = withAuth(request("post-1", "Create", "POST", "https://example.test/create"));
        loadCollections(bundle.panel, collection("Collection", post));
        queue(bundle.panel, post);
        applyUiRetryPolicy(bundle, 1, "POST", true, false, true, "");

        bundle.panel.setRunnerWarningPresenterForTests((parent, title, message) -> false);
        ImporterPanelTestSupport.invokeVoid(bundle.panel, "startRunner", new Class[]{boolean.class}, false);

        assertThat(bundle.runner.isRunning()).isFalse();
        @SuppressWarnings("unchecked")
        List<ApiRequest> queued = ImporterPanelTestSupport.getField(bundle.panel, "runnerQueuedRequests");
        assertThat(queued).hasSize(1);
    }

    @Test
    void legacyRetryRestoreShowsWarning() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        WorkspaceState state = new WorkspaceState();
        state.runnerRetries = 2;
        ImporterPanelTestSupport.invokeVoid(bundle.panel, "restoreRunnerSettings", new Class[]{WorkspaceState.class}, state);
        ImporterPanelTestSupport.setField(bundle.panel, "runnerLegacyRetryPolicyRestored", true);

        ApiRequest get = withAuth(request("get-1", "Get", "GET", "https://example.test/get"));
        loadCollections(bundle.panel, collection("Collection", get));
        queue(bundle.panel, get);

        AtomicInteger warnings = new AtomicInteger();
        bundle.panel.setRunnerWarningPresenterForTests((parent, title, message) -> {
            warnings.incrementAndGet();
            assertThat(message).contains("Legacy workspace retry count was restored");
            return false;
        });

        ImporterPanelTestSupport.invokeVoid(bundle.panel, "startRunner", new Class[]{boolean.class}, false);

        assertThat(warnings).hasValue(1);
    }

    @Test
    void invalidStatusTokenBlocksStart() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        assertThatThrownBy(() -> ImporterPanelTestSupport.invoke(bundle.panel, "parseRetryStatusCodes", new Class[]{String.class}, "200, abc"))
                .hasRootCauseMessage("Invalid retry status code: abc");
    }

    private static void loadCollections(ImporterPanel panel, ApiCollection collection) {
        @SuppressWarnings("unchecked")
        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(panel, "loadedCollections");
        loadedCollections.clear();
        loadedCollections.add(collection);
    }

    private static void queue(ImporterPanel panel, ApiRequest request) {
        panel.queueRunnerRequestsForTests(List.of(request));
    }

    private static RunnerPreviewRow rowByName(List<RunnerPreviewRow> rows, String name) {
        return rows.stream()
                .filter(row -> row != null && name.equals(row.requestName))
                .findFirst()
                .orElseThrow();
    }

    private static ApiRequest withAuth(ApiRequest request) {
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "preview-token");
        return request;
    }

    private static void applyUiRetryPolicy(ImporterPanelTestSupport.PanelBundle bundle,
                                           int maxRetries,
                                           String methods,
                                           boolean connectionFailures,
                                           boolean timeouts,
                                           boolean nonIdempotent,
                                           String statusCodes) {
        JSpinner retries = ImporterPanelTestSupport.getField(bundle.panel, "runnerMaxRetriesSpinner");
        JTextField methodsField = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryableMethodsField");
        JCheckBox connection = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryConnectionFailuresCheckBox");
        JCheckBox timeout = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryTimeoutsCheckBox");
        JCheckBox unsafe = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryNonIdempotentCheckBox");
        JTextField codes = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryStatusCodesField");
        JSpinner baseDelay = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryBaseDelaySpinner");
        JSpinner maxDelay = ImporterPanelTestSupport.getField(bundle.panel, "runnerRetryMaxDelaySpinner");

        retries.setValue(maxRetries);
        methodsField.setText(methods);
        connection.setSelected(connectionFailures);
        timeout.setSelected(timeouts);
        unsafe.setSelected(nonIdempotent);
        codes.setText(statusCodes);
        baseDelay.setValue(200);
        maxDelay.setValue(5_000);
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests = new ArrayList<>();
        for (ApiRequest request : requests) {
            collection.requests.add(request);
        }
        return collection;
    }

    private static ApiRequest request(String id, String name, String method, String url) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.method = method;
        request.url = url;
        request.sourceCollection = "Collection";
        request.sequenceOrder = 1;
        return request;
    }
}
