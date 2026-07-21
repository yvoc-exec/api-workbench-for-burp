package burp.ui;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistorySource;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryPanel;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryLoadInWorkbenchActionTest {

    @Test
    void loadingHistoryIntoWorkbenchReplacesDirtyRequestAndShowsConfirmation() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        String liveId = liveRequest.id;
        String liveName = liveRequest.name;
        String livePath = liveRequest.path;
        String liveSourceCollection = liveRequest.sourceCollection;
        int liveSequenceOrder = liveRequest.sequenceOrder;

        RequestEditorPanel requestEditor = ImporterPanelTestSupport.getField(bundle.panel, "requestEditor");
        requestEditor.markDirty();

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"loaded\"}";
        changedRequest.description = "authored history description";
        changedRequest.sourceMetadata.put("history.request.metadata", "retained-value");
        changedRequest.parameters = new ArrayList<>();
        changedRequest.parameters.add(richParameter("path", "id", "42", true));
        changedRequest.parameters.add(richParameter("query", "q", "one", false));
        changedRequest.parameters.add(richParameter("header", "X-Authored", "header", true));
        changedRequest.parameters.add(richParameter("cookie", "session", "cookie", true));
        ApiRequest.Body.FormField retainedField = new ApiRequest.Body.FormField("upload", "retained-text");
        retainedField.type = "file";
        retainedField.fileUpload = true;
        retainedField.required = true;
        retainedField.description = "field description";
        retainedField.contentType = "application/octet-stream";
        retainedField.style = "form";
        retainedField.explode = Boolean.FALSE;
        retainedField.allowReserved = true;
        retainedField.source = "history:authored";
        retainedField.sourceMetadata.put("retained.fileName", "payload.bin");
        changedRequest.body.formdata = new ArrayList<>(List.of(retainedField));
        changedRequest.body.required = true;
        changedRequest.body.description = "authored body description";
        changedRequest.body.source = "history:body";
        changedRequest.body.sourceMetadata.put("history.body.metadata", "retained-body-value");
        changedRequest.authOverrideMode = "override";
        changedRequest.explicitAuth = new ApiRequest.Auth();
        changedRequest.explicitAuth.type = "bearer";
        changedRequest.explicitAuth.properties.put("token", "authored-token");
        changedRequest.exactHttpRequest = new ExactHttpRequestSnapshot();
        changedRequest.exactHttpRequest.rawRequestBytes =
                "GET /authored HTTP/1.0\r\nHost: authored.example\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        changedRequest.exactHttpRequest.serviceHost = "authored.example";
        changedRequest.exactHttpRequest.servicePort = 443;
        changedRequest.exactHttpRequest.secure = true;
        changedRequest.exactHttpRequest.httpVersion = "HTTP/1.0";
        changedRequest.exactHttpRequest.pristine = true;
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-entry", Instant.parse("2026-06-15T01:40:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.rawRequestSent =
                "DELETE /raw-evidence HTTP/1.1\r\nHost: evidence.invalid\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText =
                "DELETE /raw-evidence HTTP/1.1\r\nHost: evidence.invalid\r\n\r\n";
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = liveRequest.id;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.confirmCalls).isEqualTo(1);
        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(requestEditor.isDirty()).isFalse();
        assertThat(requestEditor.getCurrentRequest()).isSameAs(liveRequest);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"loaded\"}");
        assertThat(liveRequest.parameters).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(changedRequest.parameters);
        assertThat(liveRequest.parameters).extracting(parameter -> parameter.location)
                .containsExactly("path", "query", "header", "cookie");
        assertThat(liveRequest.parameters).extracting(parameter -> parameter.rawKey)
                .containsExactly("raw-id", "raw-q", "raw-X-Authored", "raw-session");
        assertThat(liveRequest.parameters).extracting(parameter -> parameter.rawValue)
                .containsExactly("raw-42", "raw-one", "raw-header", "raw-cookie");
        assertThat(liveRequest.parameters.get(1).valuePresent).isFalse();
        assertThat(liveRequest.body.formdata).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(changedRequest.body.formdata);
        assertThat(liveRequest.body.required).isTrue();
        assertThat(liveRequest.body.description).isEqualTo("authored body description");
        assertThat(liveRequest.body.source).isEqualTo("history:body");
        assertThat(liveRequest.body.sourceMetadata)
                .containsEntry("history.body.metadata", "retained-body-value");
        assertThat(liveRequest.parameters.get(0).sourceMetadata)
                .containsEntry("retained.key", "retained-value");
        assertThat(liveRequest.exactHttpRequest).isNotNull().isNotSameAs(changedRequest.exactHttpRequest);
        assertThat(liveRequest.exactHttpRequest.rawRequestBytes)
                .containsExactly(changedRequest.exactHttpRequest.rawRequestBytes)
                .isNotSameAs(changedRequest.exactHttpRequest.rawRequestBytes);
        assertThat(liveRequest.id).isEqualTo(liveId);
        assertThat(liveRequest.name).isEqualTo(liveName);
        assertThat(liveRequest.path).isEqualTo(livePath);
        assertThat(liveRequest.sourceCollection).isEqualTo(liveSourceCollection);
        assertThat(liveRequest.sequenceOrder).isEqualTo(liveSequenceOrder);
        assertThat(liveRequest.description).isEqualTo("authored history description");
        assertThat(liveRequest.sourceMetadata)
                .containsEntry("history.request.metadata", "retained-value");
        assertThat(liveRequest.auth).usingRecursiveComparison().isEqualTo(changedRequest.explicitAuth);
        assertThat(liveRequest.url).doesNotContain("raw-evidence", "evidence.invalid");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchUsesUniqueFallbackWhenRequestIdMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        liveRequest.id = null;

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"fallback-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-fallback", Instant.parse("2026-06-15T01:41:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = null;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"fallback-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesStableCollectionIdentityAfterRename() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        liveCollection.name = "Petstore Renamed";
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"stable-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "stable-entry", Instant.parse("2026-06-15T01:41:30Z"));
        entry.collectionId = HistoryTestFixtures.COLLECTION_ID;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestId = liveRequest.id;
        entry.requestName = liveRequest.name;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"stable-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesLegacyCanonicalFolderPathWithoutRequestId() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-admin";
        collection.name = "Admin Collection";
        ApiRequest request = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        request.id = "req-admin";
        request.name = "List Users";
        request.path = "Admin";
        request.sourceCollection = collection.name;
        request.body.raw = "{\"username\":\"legacy\"}";
        collection.requests.add(request);
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body = new ApiRequest.Body();
        changedRequest.body.mode = request.body.mode;
        changedRequest.body.contentType = request.body.contentType;
        changedRequest.body.raw = "{\"username\":\"canonical-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "canonical-folder", Instant.parse("2026-06-15T01:41:45Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Admin/List Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"canonical-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesSameNamedFolderWhenCollectionProvesItExists() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-users";
        collection.name = "Users API";
        collection.folderPaths = new java.util.ArrayList<>(List.of("Users"));

        ApiRequest request = new ApiRequest();
        request.id = "req-users";
        request.name = "Users";
        request.path = "Users";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/users";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"same-folder\"}";
        collection.requests.add(request);

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body.raw = "{\"username\":\"same-folder-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "same-folder-entry", Instant.parse("2026-06-15T01:41:15Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"same-folder-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchResolvesRootRequestWithLegacySameNameFolderPath() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = new ApiCollection();
        collection.id = "col-root-users";
        collection.name = "Users API";

        ApiRequest request = new ApiRequest();
        request.id = "req-root-users";
        request.name = "Users";
        request.path = "";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/users";
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"root\"}";
        collection.requests.add(request);

        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(request);
        changedRequest.body.raw = "{\"username\":\"root-loaded\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "root-legacy", Instant.parse("2026-06-15T01:41:20Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.requestSnapshot.authoredRequest.id = null;
        entry.requestId = null;
        entry.requestName = request.name;
        entry.folderPath = "Users";

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"root-loaded\"}");
        assertThat(collectionNames(loadedCollections)).doesNotContain("History Replays");
    }

    @Test
    void loadingHistoryIntoWorkbenchCreatesHistoryReplaysWhenOriginalMissing() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest removedRequest = liveCollection.requests.remove(0);

        ApiRequest changedRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        changedRequest.body.raw = "{\"username\":\"fallback-request\"}";
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-missing", Instant.parse("2026-06-15T01:42:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(changedRequest);
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = removedRequest.id;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(notifier.loadedReplayCalls).isEqualTo(1);
        assertThat(collectionNames(loadedCollections)).contains("History Replays");
        ApiCollection historyReplays = loadedCollections.stream()
                .filter(collection -> "History Replays".equals(collection.name))
                .findFirst()
                .orElseThrow();
        assertThat(historyReplays.requests).hasSize(1);
        ApiRequest recreated = historyReplays.requests.get(0);
        assertThat(recreated.name).isEqualTo(HistoryTestFixtures.REQUEST_NAME);
        assertThat(recreated.path).isEqualTo(HistoryTestFixtures.REQUEST_FOLDER);
        assertThat(recreated.body.raw).isEqualTo("{\"username\":\"fallback-request\"}");
        assertThat(liveCollection.requests).hasSize(1);
        assertThat(liveCollection.requests).doesNotContain(removedRequest);
    }

    @Test
    void loadingExactHistoryIntoWorkbenchKeepsExactFramingAndDoesNotWarn() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        bundle.panel.replaceEnvironmentProfiles(List.of(HistoryTestFixtures.sampleEnvironment()));
        bundle.panel.setActiveEnvironmentId(HistoryTestFixtures.ENVIRONMENT_ID);

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        RequestEditorPanel requestEditor = ImporterPanelTestSupport.getField(bundle.panel, "requestEditor");
        AtomicInteger warnings = new AtomicInteger();
        requestEditor.setExactTransportWarningProviderForTests((parent, title, message) -> {
            warnings.incrementAndGet();
            return true;
        });

        ApiRequest liveRequest = collection.requests.get(0);
        ApiRequest exact = HistoryTestFixtures.copyRequest(liveRequest);
        exact.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        exact.url = "https://api.example.test/search?q=hello%20world&path=a%2Fb&plus=%2B&pct=%25&empty=&flag&repeat=1&repeat=2#fragment";
        exact.description = "exact history";
        exact.variables = new ArrayList<>();
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = "tenant";
        variable.value = "acme";
        variable.type = "string";
        variable.enabled = false;
        exact.variables.add(variable);
        exact.headers = new ArrayList<>(List.of(
                new ApiRequest.Header("Host", "authored.example.test", false),
                new ApiRequest.Header("Host", "duplicate.example.test", false),
                new ApiRequest.Header("Content-Length", "123", false),
                new ApiRequest.Header("Content-Length", "456", false),
                new ApiRequest.Header("Transfer-Encoding", "chunked", false),
                new ApiRequest.Header("Cookie", "a=1", false),
                new ApiRequest.Header("Cookie", "b=2", false),
                new ApiRequest.Header("X-Disabled", "skip", true)
        ));
        exact.body = new ApiRequest.Body();
        exact.body.mode = "raw";
        exact.body.raw = "{\"exact\":true}";
        exact.body.contentType = "application/json";
        exact.preRequestScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "pre-one();")));
        exact.postResponseScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "post-one();")));

        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "exact-history", Instant.parse("2026-06-15T01:44:00Z"));
        entry.collectionId = collection.id;
        entry.collectionName = collection.name;
        entry.requestId = liveRequest.id;
        entry.requestName = liveRequest.name;
        entry.folderPath = liveRequest.path;
        entry.requestSnapshot = HistoryRequestSnapshot.from(exact);
        entry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, entry);

        assertThat(warnings).hasValue(0);
        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(requestEditor.getCurrentRequest()).isNotNull();
        assertThat(requestEditor.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(requestEditor.isExactTransportHeadersSelectedForTests()).isTrue();
        assertThat(requestEditor.getExactTransportIndicatorForTests().isVisible()).isTrue();
        assertThat(requestEditor.getCurrentRequest().url).isEqualTo(exact.url);
        assertThat(requestEditor.getCurrentRequest().description).isEqualTo("exact history");
        assertThat(requestEditor.getCurrentRequest().headers)
                .extracting(header -> header.key + ":" + header.value + "|" + header.disabled)
                .containsExactly(
                        "Host:authored.example.test|false",
                        "Host:duplicate.example.test|false",
                        "Content-Length:123|false",
                        "Content-Length:456|false",
                        "Transfer-Encoding:chunked|false",
                        "Cookie:a=1|false",
                        "Cookie:b=2|false",
                        "X-Disabled:skip|true");
        assertThat(requestEditor.getCurrentRequest().variables)
                .extracting(variable1 -> variable1.key + ":" + variable1.value + "|" + variable1.type + "|" + variable1.enabled)
                .containsExactly("tenant:acme|string|false");
        assertThat(requestEditor.getCurrentRequest().body.raw).isEqualTo("{\"exact\":true}");

        String loadedSnapshot = snapshot(liveRequest);

        SwingUtilities.invokeAndWait(() -> toggleExactTransport(requestEditor));
        SwingUtilities.invokeAndWait(() -> toggleExactTransport(requestEditor));

        assertThat(snapshot(liveRequest)).isEqualTo(loadedSnapshot);
        assertThat(requestEditor.getCurrentRequest().buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
    }

    @Test
    void loadInWorkbenchClearsDescriptionAndVariablesFromSnapshot() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(HistoryTestFixtures.sampleCollection())));

        RecordingNotifier notifier = new RecordingNotifier();
        ImporterPanelTestSupport.setField(bundle.panel, "historyLoadResultNotifier", notifier);

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        liveRequest.description = "Old description";
        liveRequest.variables = new ArrayList<>();
        liveRequest.variables.add(variable("oldToken", "abc"));
        liveRequest.variables.add(variable("stale", "true"));

        ApiRequest emptySnapshotRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        emptySnapshotRequest.description = null;
        emptySnapshotRequest.variables = new ArrayList<>();
        emptySnapshotRequest.body.raw = "{\"username\":\"loaded\"}";

        HistoryEntry clearEntry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-clear", Instant.parse("2026-06-15T01:41:00Z"));
        clearEntry.collectionId = liveCollection.id;
        clearEntry.collectionName = liveCollection.name;
        clearEntry.requestId = liveRequest.id;
        clearEntry.requestName = liveRequest.name;
        clearEntry.folderPath = liveRequest.path;
        clearEntry.requestSnapshot = HistoryRequestSnapshot.from(emptySnapshotRequest);
        clearEntry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, clearEntry);

        assertThat(notifier.confirmCalls).isEqualTo(1);
        assertThat(notifier.loadedOriginalCalls).isEqualTo(1);
        assertThat(liveRequest.description).isNull();
        assertThat(liveRequest.variables).isNotNull().isEmpty();
        assertThat(variableKeys(liveRequest.variables)).doesNotContain("oldToken", "stale");
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"loaded\"}");

        ApiRequest variableSnapshotRequest = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        variableSnapshotRequest.description = null;
        variableSnapshotRequest.variables = new ArrayList<>();
        variableSnapshotRequest.variables.add(variable("newToken", "xyz"));
        variableSnapshotRequest.body.raw = "{\"username\":\"loaded-again\"}";

        HistoryEntry variableEntry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "load-variable", Instant.parse("2026-06-15T01:42:00Z"));
        variableEntry.collectionId = liveCollection.id;
        variableEntry.collectionName = liveCollection.name;
        variableEntry.requestId = liveRequest.id;
        variableEntry.requestName = liveRequest.name;
        variableEntry.folderPath = liveRequest.path;
        variableEntry.requestSnapshot = HistoryRequestSnapshot.from(variableSnapshotRequest);
        variableEntry.source = HistorySource.WORKBENCH;

        clickLoadHistoryButton(bundle, variableEntry);

        assertThat(notifier.loadedOriginalCalls).isEqualTo(2);
        assertThat(liveRequest.description).isNull();
        assertThat(liveRequest.variables).hasSize(1);
        assertThat(liveRequest.variables.get(0).key).isEqualTo("newToken");
        assertThat(liveRequest.variables.get(0).value).isEqualTo("xyz");
        assertThat(liveRequest.body.raw).isEqualTo("{\"username\":\"loaded-again\"}");

        variableEntry.requestSnapshot.authoredRequest.variables.get(0).value = "mutated-after-load";
        assertThat(liveRequest.variables.get(0).value).isEqualTo("xyz");
    }

    @Test
    void ambiguousFallbackMatchDoesNotSilentlyChooseARequest() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        ApiRequest duplicate = HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest());
        duplicate.id = "duplicate-request";
        collection.requests.add(duplicate);
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));

        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "ambiguous", Instant.parse("2026-06-15T01:43:00Z"));
        entry.requestSnapshot = HistoryRequestSnapshot.from(HistoryTestFixtures.copyRequest(HistoryTestFixtures.sampleRequest()));
        entry.requestSnapshot.authoredRequest.id = null;
        entry.collectionName = HistoryTestFixtures.COLLECTION_NAME;
        entry.requestId = null;
        entry.requestName = HistoryTestFixtures.REQUEST_NAME;
        entry.folderPath = HistoryTestFixtures.REQUEST_FOLDER;

        Object context = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "resolveHistoryRequestContext",
                new Class<?>[]{HistoryEntry.class, boolean.class},
                entry,
                false);

        assertThat(context).isNotNull();
        assertThat((Boolean) ImporterPanelTestSupport.getField(context, "originalRequestExists")).isFalse();
        assertThat((Boolean) ImporterPanelTestSupport.getField(context, "ambiguousResolution")).isTrue();
        assertThat((Object) ImporterPanelTestSupport.getField(context, "request")).isNull();
        assertThat(collectionNames(ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections")))
                .doesNotContain("History Replays");
    }

    private static String snapshot(ApiRequest request) {
        if (request == null) {
            return "null";
        }
        return "id=" + request.id
                + "|name=" + request.name
                + "|path=" + request.path
                + "|sourceCollection=" + request.sourceCollection
                + "|description=" + request.description
                + "|method=" + request.method
                + "|url=" + request.url
                + "|headers=" + (request.headers != null ? request.headers : List.<ApiRequest.Header>of()).stream()
                .map(header -> header.key + ":" + header.value + "|" + header.disabled)
                .toList()
                + "|bodyMode=" + (request.body != null ? request.body.mode : null)
                + "|bodyRaw=" + (request.body != null ? request.body.raw : null)
                + "|contentType=" + (request.body != null ? request.body.contentType : null)
                + "|variables=" + (request.variables != null ? request.variables : List.<ApiRequest.Variable>of()).stream()
                .map(variable -> variable.key + ":" + variable.value + "|" + variable.type + "|" + variable.enabled)
                .toList()
                + "|pre=" + (request.preRequestScripts != null ? request.preRequestScripts : List.<ApiRequest.Script>of()).stream().map(script -> script.type + ":" + script.exec).toList()
                + "|post=" + (request.postResponseScripts != null ? request.postResponseScripts : List.<ApiRequest.Script>of()).stream().map(script -> script.type + ":" + script.exec).toList()
                + "|buildMode=" + request.buildMode
                + "|disabled=" + request.disabled
                + "|sequenceOrder=" + request.sequenceOrder
                + "|editorMaterialized=" + request.editorMaterialized
                + "|suppressed=" + new LinkedHashSet<>(request.suppressedAutoHeaders);
    }

    private static void toggleExactTransport(RequestEditorPanel editor) {
        JPopupMenu menu = editor.createSendDropdownMenuForTests();
        for (java.awt.Component component : menu.getComponents()) {
            if (component instanceof JCheckBoxMenuItem item
                    && "Exact transport headers \u2014 Advanced".equals(item.getText())) {
                item.doClick();
                return;
            }
        }
        throw new AssertionError("Exact transport menu item not found");
    }

    private static List<String> collectionNames(List<ApiCollection> collections) {
        return collections.stream()
                .map(collection -> collection != null ? collection.name : null)
                .toList();
    }

    private static List<String> variableKeys(List<ApiRequest.Variable> variables) {
        List<String> keys = new ArrayList<>();
        if (variables == null) {
            return keys;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable != null && variable.key != null) {
                keys.add(variable.key);
            }
        }
        return keys;
    }

    private static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        return variable;
    }

    private static ApiRequest.Parameter richParameter(String location, String key,
                                                      String value, boolean valuePresent) {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
        parameter.rawKey = "raw-" + key;
        parameter.rawValue = "raw-" + value;
        parameter.valuePresent = valuePresent;
        parameter.disabled = "header".equals(location);
        parameter.required = true;
        parameter.type = "string";
        parameter.format = "wave5";
        parameter.description = "authored parameter";
        parameter.style = "form";
        parameter.explode = Boolean.TRUE;
        parameter.allowReserved = true;
        parameter.source = "history:authored";
        parameter.sourceMetadata.put("retained.key", "retained-value");
        return parameter;
    }

    private static void clickLoadHistoryButton(ImporterPanelTestSupport.PanelBundle bundle, HistoryEntry entry) throws Exception {
        HistoryPanel historyPanel = bundle.panel.getHistoryPanelForTests();
        historyPanel.getHistoryStore().addEntry(entry);
        historyPanel.refreshFromStore(entry.id);
        ImporterPanelTestSupport.awaitEdt();
        SwingUtilities.invokeAndWait(() -> {
            historyPanel.getHistoryTable().setRowSelectionInterval(0, 0);
            historyPanel.getActionsPanel().getLoadButton().doClick();
        });
        ImporterPanelTestSupport.awaitEdt();
    }

    private static final class RecordingNotifier extends burp.ui.history.HistoryLoadResultNotifier {
        int confirmCalls;
        int loadedOriginalCalls;
        int loadedReplayCalls;

        @Override
        public boolean confirmReplaceCurrentRequest(java.awt.Component parent) {
            confirmCalls++;
            return true;
        }

        @Override
        public void showLoadedIntoOriginalRequest(java.awt.Component parent, HistoryEntry entry) {
            loadedOriginalCalls++;
        }

        @Override
        public void showLoadedUnderHistoryReplays(java.awt.Component parent, String requestName) {
            loadedReplayCalls++;
        }

        @Override
        public void showError(java.awt.Component parent, String message) {
        }

        @Override
        public void showInfo(java.awt.Component parent, String message) {
        }

        @Override
        public boolean confirmClearHistory(java.awt.Component parent) {
            return true;
        }

        @Override
        public boolean confirmExportSensitiveData(java.awt.Component parent) {
            return true;
        }
    }
}
