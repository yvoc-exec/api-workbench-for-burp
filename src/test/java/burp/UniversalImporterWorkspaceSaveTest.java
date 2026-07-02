package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.utils.DebouncedSwingAction;
import burp.utils.WorkspaceStateJson;
import burp.utils.WorkspaceStateService;
import burp.ui.ImporterPanel;
import burp.ui.RequestEditorPanel;
import burp.ui.tree.CollectionTreeNode;
import burp.testsupport.ImporterPanelTestSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalImporterWorkspaceSaveTest {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void rapidWorkspaceChangeRequestsCollapseIntoSingleWrite() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 100);

        // Trigger multiple rapid save requests
        for (int i = 0; i < 10; i++) {
            importer.requestWorkspaceStateSave();
        }

        assertThat(writeCount.get()).isZero();

        // Wait for debounce to fire
        Thread.sleep(200);

        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void unchangedWorkspaceStateDoesNotWriteAgain() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // First save should write
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);

        // Second save with identical state should skip
        importer.saveWorkspaceState();
        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void cleanupFlushesPendingWorkspaceSave() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        // Speed up debounce for testing
        setDebounceDelay(importer, 5000);

        // Request a save but don't let the timer fire
        importer.requestWorkspaceStateSave();
        assertThat(writeCount.get()).isZero();

        // Cleanup should flush immediately
        importer.cleanup();
        assertThat(writeCount.get()).isEqualTo(1);

        // Timer should no longer fire
        Thread.sleep(100);
        assertThat(writeCount.get()).isEqualTo(1);
    }

    @Test
    void debouncedWorkspaceSaveFailureLogsOnceAndPreservesPreviousPersistedState() throws Exception {
        WorkspaceSaveFailureFixture fixture = newFailureFixture();
        try {
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("baseline-workspace", "Baseline"));
            fixture.importer.requestWorkspaceStateSaveNow();
            awaitSuccessfulWrites(fixture.store, 1);

            String baselineJson = fixture.store.currentValue();
            assertThat(workspaceNameFromJson(baselineJson)).isEqualTo("baseline-workspace");
            assertThat(lastSavedWorkspaceJson(fixture.importer)).isEqualTo(baselineJson);

            fixture.store.failNextWrite("debounced boom");
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("failed-workspace", "Failed"));
            setDebounceDelay(fixture.importer, 100);
            fixture.importer.requestWorkspaceStateSave();

            awaitErrorCount(fixture.logs, 1);
            assertThat(fixture.logs.errors).singleElement().satisfies(error -> assertThat(error)
                    .contains("Workspace state save failed")
                    .contains("debounced boom"));
            assertThat(fixture.store.currentValue()).isEqualTo(baselineJson);
            assertThat(lastSavedWorkspaceJson(fixture.importer)).isEqualTo(baselineJson);
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void workspaceSaveExecutorRemainsUsableAndLaterSaveSucceedsAfterBackgroundFailure() throws Exception {
        WorkspaceSaveFailureFixture fixture = newFailureFixture();
        try {
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("baseline-workspace", "Baseline"));
            fixture.importer.requestWorkspaceStateSaveNow();
            awaitSuccessfulWrites(fixture.store, 1);

            fixture.store.failNextWrite("transient workspace failure");
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("failed-workspace", "Failed"));
            setDebounceDelay(fixture.importer, 100);
            fixture.importer.requestWorkspaceStateSave();

            awaitErrorCount(fixture.logs, 1);
            assertThat(workspaceNameFromJson(fixture.store.currentValue())).isEqualTo("baseline-workspace");

            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("recovered-workspace", "Recovered"));
            fixture.importer.requestWorkspaceStateSaveNow();
            awaitSuccessfulWrites(fixture.store, 2);

            String recoveredJson = fixture.store.currentValue();
            assertThat(workspaceNameFromJson(recoveredJson)).isEqualTo("recovered-workspace");
            assertThat(lastSavedWorkspaceJson(fixture.importer)).isEqualTo(recoveredJson);
            assertThat(fixture.logs.errors).hasSize(1);
            assertThat(isWorkspaceSaveExecutorTerminated(fixture.importer)).isFalse();
        } finally {
            fixture.importer.cleanup();
        }
    }

    @Test
    void exactBuildModeSaveUsesModelSnapshotWithoutApplyingPendingEditorState() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicReference<String> lastJson = new AtomicReference<>();
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            lastJson.set(inv.getArgument(1));
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        setDebounceDelay(importer, 500);
        ImporterPanel ui = importer.getUI();

        ApiCollection collection = new ApiCollection();
        collection.id = "col-exact-toggle";
        collection.name = "Exact Toggle";
        ApiRequest request = new ApiRequest();
        request.id = "req-exact-toggle";
        request.name = "Exact Toggle Request";
        request.method = "POST";
        request.url = "https://api.example.test/original?q=hello%20world&path=a%2Fb&plus=%2B";
        request.description = "Original description";
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.editorMaterialized = true;
        request.variables = new ArrayList<>();
        request.variables.add(variable("token", "original-token", "string", false));
        request.headers = new ArrayList<>();
        request.headers.add(new ApiRequest.Header("Accept", "application/json"));
        request.headers.add(new ApiRequest.Header("X-Original", "one"));
        request.headers.add(new ApiRequest.Header("Content-Type", "text/plain"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "original-body";
        request.body.contentType = "text/plain";
        request.preRequestScripts = new ArrayList<>(List.of(new ApiRequest.Script("js", "console.log(\"original\");")));
        collection.requests.add(request);

        ui.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(collection)));
        SwingUtilities.invokeAndWait(() -> { });

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(ui, "loadedCollections");
        ApiCollection liveCollection = loadedCollections.get(0);
        ApiRequest liveRequest = liveCollection.requests.get(0);

        JTree tree = requestTree(ui);
        CollectionTreeNode requestNode = findRequestNode((DefaultMutableTreeNode) tree.getModel().getRoot(), liveRequest.id);
        assertThat(requestNode).isNotNull();
        SwingUtilities.invokeAndWait(() -> tree.setSelectionPath(new TreePath(requestNode.getPath())));
        RequestEditorPanel editor = requestEditor(ui);
        ImporterPanelTestSupport.awaitCondition(
                () -> editor.getCurrentRequest() != null && liveRequest.id.equals(editor.getCurrentRequest().id),
                Duration.ofSeconds(3));
        SwingUtilities.invokeAndWait(() -> { });

        SwingUtilities.invokeAndWait(() -> {
            headersModel(editor).setValueAt("application/xml", findRow(headersModel(editor), "Accept"), 1);
            headersModel(editor).addRow(new Object[]{"X-Duplicate", "one"});
            headersModel(editor).addRow(new Object[]{"X-Duplicate", "two"});
            editor.getUrlField().setText("https://api.example.test/pending?q=pending%20value&path=x%2Fy&plus=%2B");
            paramsModel(editor).setRowCount(0);
            paramsModel(editor).addRow(new Object[]{"q", "pending value"});
            paramsModel(editor).addRow(new Object[]{"path", "x/y"});
            paramsModel(editor).addRow(new Object[]{"plus", "+"});
            bodyRawArea(editor).setText("pending-body");
            preScriptArea(editor).setText("console.log(\"pending\");");
        });
        SwingUtilities.invokeAndWait(() -> { });

        ApiRequest snapshotBeforeToggle = GSON.fromJson(GSON.toJson(liveRequest), ApiRequest.class);

        writeCount.set(0);
        lastJson.set(null);

        clickExactTransport(editor);
        awaitWriteCount(writeCount, 1);

        assertThat(liveRequest.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(liveRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
        assertThat(preScriptArea(editor).getText()).isEqualTo("console.log(\"pending\");");

        WorkspaceState saved = WorkspaceStateJson.fromJson(lastJson.get());
        ApiRequest savedRequest = saved.collections.get(0).requests.get(0);
        assertThat(savedRequest.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(savedRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
        assertThat(headerRows(headersModel(editor))).containsExactly(
                "Accept=application/xml",
                "X-Original=one",
                "Content-Type=text/plain",
                "X-Duplicate=one",
                "X-Duplicate=two");
        assertThat(editor.getUrlField().getText()).isEqualTo("https://api.example.test/pending?q=pending%20value&path=x%2Fy&plus=%2B");
        assertThat(bodyRawArea(editor).getText()).isEqualTo("pending-body");
        assertThat(preScriptArea(editor).getText()).isEqualTo("console.log(\"pending\");");

        long quietDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(650);
        ImporterPanelTestSupport.awaitCondition(() -> System.nanoTime() >= quietDeadline, Duration.ofSeconds(2));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(writeCount.get()).isEqualTo(1);
        assertThat(liveRequest.buildMode).isEqualTo(ApiRequest.BuildMode.EXACT_HTTP);
        assertThat(liveRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
        assertThat(preScriptArea(editor).getText()).isEqualTo("console.log(\"pending\");");

        writeCount.set(0);
        lastJson.set(null);
        clickExactTransport(editor);
        awaitWriteCount(writeCount, 1);

        WorkspaceState savedAfterToggleOff = WorkspaceStateJson.fromJson(lastJson.get());
        ApiRequest restoredRequest = savedAfterToggleOff.collections.get(0).requests.get(0);
        assertThat(restoredRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(restoredRequest).usingRecursiveComparison()
                .ignoringFields("buildMode")
                .isEqualTo(snapshotBeforeToggle);
    }

    @Test
    void cleanupTerminatesWorkspaceSaveWorkerAfterBackgroundFailure() throws Exception {
        WorkspaceSaveFailureFixture fixture = newFailureFixture();
        try {
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("baseline-workspace", "Baseline"));
            fixture.importer.requestWorkspaceStateSaveNow();
            awaitSuccessfulWrites(fixture.store, 1);

            fixture.store.failNextWrite("cleanup failure");
            applyWorkspaceStateWithoutAutoSave(fixture.importer, workspaceState("cleanup-workspace", "Cleanup"));
            setDebounceDelay(fixture.importer, 100);
            fixture.importer.requestWorkspaceStateSave();

            awaitErrorCount(fixture.logs, 1);
            fixture.importer.cleanup();

            assertThat(isWorkspaceSaveExecutorTerminated(fixture.importer)).isTrue();
            assertThat(fixture.store.currentValue()).isNotBlank();
            assertThat(workspaceNameFromJson(fixture.store.currentValue()))
                    .isIn("baseline-workspace", "cleanup-workspace");
        } finally {
            if (!isWorkspaceSaveExecutorTerminated(fixture.importer)) {
                fixture.importer.cleanup();
            }
        }
    }

    void cleanupPersistsRequestEditorStateBeforeFinalWorkspaceSave() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        AtomicReference<Boolean> shutdownSeenDuringSave = new AtomicReference<>(Boolean.FALSE);
        Mockito.reset(fixture.persistedObject);
        Mockito.doAnswer(inv -> {
            fixture.writeCount.incrementAndGet();
            fixture.lastJson.set(inv.getArgument(1));
            shutdownSeenDuringSave.set((Boolean) privateField(fixture.ui, "shuttingDown"));
            return null;
        }).when(fixture.persistedObject).setString(Mockito.anyString(), Mockito.anyString());

        fixture.importer.cleanup();
        awaitWriteCount(fixture.writeCount, 1);
        assertThat(shutdownSeenDuringSave.get()).isTrue();
    }

    @Test
    void structuralCollectionChangesPersistImmediatelyIncludingExpandedTreeState() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicReference<String> lastJson = new AtomicReference<>();
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            lastJson.set(inv.getArgument(1));
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        setDebounceDelay(importer, 5000);
        ImporterPanel ui = importer.getUI();

        Path tempJson = Files.createTempFile(Path.of("target"), "nested-postman-", ".json").toAbsolutePath().normalize();
        Files.writeString(tempJson, """
                {
                  "info": {
                    "name": "Nested Demo",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "Auth",
                      "item": [
                        {
                          "name": "OAuth",
                          "item": [
                            {
                              "name": "Get Token",
                              "request": {
                                "method": "GET",
                                "url": "https://auth.example.test/token"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        tempJson.toFile().deleteOnExit();

        invokePrivateLoadCollection(ui, tempJson.toFile());
        awaitWriteCount(writeCount, 1);

        WorkspaceState imported = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(imported.collections).hasSize(1);
        assertThat(imported.collections.get(0).requests).hasSize(1);
        assertThat(imported.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");
        assertThat(imported.requestTreePaths).containsValue("Auth/OAuth");

        JTree tree = requestTree(ui);
        TreePath oauthPath = findFolderPath(tree, "Nested Demo", "Auth", "OAuth");
        assertThat(oauthPath).isNotNull();

        SwingUtilities.invokeAndWait(() -> tree.collapsePath(oauthPath));
        awaitWriteCount(writeCount, 2);

        SwingUtilities.invokeAndWait(() -> tree.expandPath(oauthPath));
        awaitWriteCount(writeCount, 3);

        WorkspaceState afterExpansion = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(afterExpansion.expandedTreePathKeys).contains(
                workspaceTreePathKey("Nested Demo", "Auth/OAuth")
        );

        @SuppressWarnings("unchecked")
        List<ApiCollection> liveCollections = (List<ApiCollection>) privateField(ui, "loadedCollections");
        invokePrivateRemoveCollections(ui, List.of(liveCollections.get(0)));
        awaitWriteCount(writeCount, 4);

        WorkspaceState afterRemoval = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(afterRemoval.collections).isEmpty();
    }

    @Test
    void restoredNormalizedTreePathsArePersistedOnceAfterFinalization() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicReference<String> lastJson = new AtomicReference<>();
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            lastJson.set(inv.getArgument(1));
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        setDebounceDelay(importer, 5000);
        ImporterPanel ui = importer.getUI();

        WorkspaceState state = nestedWorkspaceState();
        applyWorkspaceRequestTreePathsToRequests(state.collections, state.requestTreePaths);
        ui.restoreWorkspaceCollections(state.collections);

        Object pendingRestore = createPendingRestore(ui, state);
        setField(pendingRestore, "repairedRequestPathCount", 1);

        Method finalizeMethod = ImporterPanel.class.getDeclaredMethod("finalizeRestoredMainRequestTree",
                pendingRestore.getClass());
        finalizeMethod.setAccessible(true);
        finalizeMethod.invoke(ui, pendingRestore);

        awaitWriteCount(writeCount, 1);

        WorkspaceState saved = WorkspaceStateJson.fromJson(lastJson.get());
        assertThat(saved.collections).hasSize(1);
        assertThat(saved.collections.get(0).requests).hasSize(1);
        assertThat(saved.collections.get(0).requests.get(0).path).isEqualTo("Auth/OAuth/Get Token");
        assertThat(saved.requestTreePaths).containsEntry(
                workspaceRequestTreePathKey("APIM", 0, saved.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
    }

    @Test
    void workspaceRestoreWaitsUntilUiRegistrationHook() throws Exception {
        AtomicInteger readCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            readCount.incrementAndGet();
            return null;
        }).when(persistedObject).getString(Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        assertThat(readCount.get()).isZero();

        Method restoreWorkspaceStateAfterUiRegistration = UniversalImporter.class.getDeclaredMethod("restoreWorkspaceStateAfterUiRegistration");
        restoreWorkspaceStateAfterUiRegistration.setAccessible(true);
        restoreWorkspaceStateAfterUiRegistration.invoke(importer);
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(readCount.get()).isEqualTo(1);
    }

    @Test
    void importerReusesTheSameWorkspaceStateServiceInstanceForRestoreAndSave() throws Exception {
        AtomicInteger readCount = new AtomicInteger(0);
        AtomicInteger writeCount = new AtomicInteger(0);
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            readCount.incrementAndGet();
            return WorkspaceStateJson.toJson(new WorkspaceState());
        }).when(persistedObject).getString(Mockito.anyString());
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);
        UniversalImporter importer = new UniversalImporter(mockApi(), burp.utils.ScriptMode.DISABLED, service);

        Field serviceField = UniversalImporter.class.getDeclaredField("workspaceStateService");
        serviceField.setAccessible(true);
        assertThat(serviceField.get(importer)).isSameAs(service);

        Method restoreWorkspaceStateAfterUiRegistration = UniversalImporter.class.getDeclaredMethod("restoreWorkspaceStateAfterUiRegistration");
        restoreWorkspaceStateAfterUiRegistration.setAccessible(true);
        restoreWorkspaceStateAfterUiRegistration.invoke(importer);
        SwingUtilities.invokeAndWait(() -> { });
        importer.requestWorkspaceStateSaveNow();

        assertThat(readCount.get()).isEqualTo(1);
        assertThat(writeCount.get()).isEqualTo(1);
        assertThat(serviceField.get(importer)).isSameAs(service);
    }

    @Test
    void deletingAuthorizationTriggersImmediateWorkspaceSave() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.lastJson.get());
        ApiRequest savedRequest = saved.collections.get(0).requests.get(0);
        assertThat(savedRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(savedRequest.editorMaterialized).isTrue();
        assertThat(savedRequest.suppressedAutoHeaders).containsExactly("authorization");
        assertThat(savedRequest.headers).extracting(h -> h.key).doesNotContain("Authorization");
    }

    @Test
    void workspaceJsonAfterDeletingAuthorizationHasSuppressionAndNoAuthorization() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        assertThat(fixture.lastJson.get()).contains("\"suppressedAutoHeaders\"");
        assertThat(fixture.lastJson.get()).contains("\"authorization\"");
        assertThat(fixture.lastJson.get()).doesNotContain("\"Authorization\"");
    }

    @Test
    void workspaceSnapshotIncludesEnvironmentProfilesAndActiveSelection() throws Exception {
        ImporterPanel ui = newImporterUi();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "UAT";
        profile.variables.put("baseUrl", "https://uat.example.test");
        profile.oauth2.config.put("oauth2_client_id", "client-id");
        ui.replaceEnvironmentProfiles(List.of(profile));
        ui.setActiveEnvironmentId(profile.id);

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();

        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments.get(0).variables).containsEntry("baseUrl", "https://uat.example.test");
        assertThat(snapshot.environments.get(0).oauth2.config).containsEntry("oauth2_client_id", "client-id");
    }

    @Test
    void workspaceRestoreRestoresActiveEnvironmentSelection() throws Exception {
        ImporterPanel ui = newImporterUi();
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "PRD";
        profile.variables.put("baseUrl", "https://prd.example.test");
        profile.ensureId();
        WorkspaceState state = new WorkspaceState();
        state.collections = List.of(new ApiCollection());
        state.environments = List.of(profile);
        state.activeEnvironmentId = profile.id;

        ui.restoreWorkspaceState(state);
        SwingUtilities.invokeAndWait(() -> { });

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.environments.get(0).variables).containsEntry("baseUrl", "https://prd.example.test");
    }

    @Test
    void workspaceRestoreRestoresEnvironmentProfilesWithoutCollections() throws Exception {
        ImporterPanel ui = newImporterUi();

        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "Env Only";
        profile.variables.put("baseUrl", "https://env-only.example.test");
        profile.ensureId();

        WorkspaceState state = new WorkspaceState();
        state.collections = java.util.Collections.emptyList();
        state.environments = java.util.List.of(profile);
        state.activeEnvironmentId = profile.id;

        ui.restoreWorkspaceState(state);
        SwingUtilities.invokeAndWait(() -> { });

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.collections).isEmpty();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments.get(0).variables)
                .containsEntry("baseUrl", "https://env-only.example.test");
    }

    @Test
    void queuedWorkspaceRestoreDoesNotOverwriteNewerEnvironmentState() throws Exception {
        WorkspaceState oldState = new WorkspaceState();
        EnvironmentProfile oldProfile = new EnvironmentProfile();
        oldProfile.name = "Old";
        oldProfile.variables.put("baseUrl", "https://old.example.test");
        oldProfile.ensureId();
        oldState.environments = List.of(oldProfile);
        oldState.activeEnvironmentId = oldProfile.id;
        String oldJson = WorkspaceStateJson.toJson(oldState);

        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.when(persistedObject.getString(Mockito.anyString())).thenReturn(oldJson);
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        ImporterPanel ui = importer.getUI();

        EnvironmentProfile newerProfile = new EnvironmentProfile();
        newerProfile.name = "New";
        newerProfile.variables.put("baseUrl", "https://new.example.test");
        newerProfile.ensureId();

        SwingUtilities.invokeAndWait(() -> {
            invokeRestoreWorkspaceStateAfterUiRegistration(importer);
            ui.replaceEnvironmentProfiles(List.of(newerProfile));
            ui.setActiveEnvironmentId(newerProfile.id);
            importer.requestWorkspaceStateSaveNow();
        });

        SwingUtilities.invokeAndWait(() -> { });

        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.activeEnvironmentId).isEqualTo(newerProfile.id);
        assertThat(snapshot.environments.get(0).variables).containsEntry("baseUrl", "https://new.example.test");
    }

    @Test
    void queuedWorkspaceRestoreSkipsWhenEnvironmentEditorDirty() throws Exception {
        WorkspaceState oldState = new WorkspaceState();
        EnvironmentProfile oldProfile = new EnvironmentProfile();
        oldProfile.name = "Old";
        oldProfile.variables.put("baseUrl", "https://old.example.test");
        oldProfile.ensureId();
        oldState.environments = List.of(oldProfile);
        oldState.activeEnvironmentId = oldProfile.id;
        String oldJson = WorkspaceStateJson.toJson(oldState);

        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.when(persistedObject.getString(Mockito.anyString())).thenReturn(oldJson);
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        ImporterPanel ui = importer.getUI();

        SwingUtilities.invokeAndWait(() -> {
            invokeRestoreWorkspaceStateAfterUiRegistration(importer);
            try {
                JTextArea rawArea = (JTextArea) privateField(ui, "environmentRawArea");
                rawArea.setText("baseUrl=https://draft.example.test");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        SwingUtilities.invokeAndWait(() -> { });

        JTextArea rawArea = (JTextArea) privateField(ui, "environmentRawArea");
        assertThat(rawArea.getText()).contains("baseUrl=https://draft.example.test");
    }

    @Test
    void importerRestoresEnvironmentOnlyWorkspaceStateAfterUiRegistration() throws Exception {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = "Env Only";
        profile.variables.put("baseUrl", "https://env-only.example.test");
        profile.ensureId();

        WorkspaceState state = new WorkspaceState();
        state.collections = java.util.Collections.emptyList();
        state.environments = java.util.List.of(profile);
        state.activeEnvironmentId = profile.id;
        String json = WorkspaceStateJson.toJson(state);

        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.when(persistedObject.getString(Mockito.anyString())).thenReturn(json);
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);

        Method restoreWorkspaceStateAfterUiRegistration = UniversalImporter.class.getDeclaredMethod("restoreWorkspaceStateAfterUiRegistration");
        restoreWorkspaceStateAfterUiRegistration.setAccessible(true);
        restoreWorkspaceStateAfterUiRegistration.invoke(importer);
        SwingUtilities.invokeAndWait(() -> { });

        ImporterPanel ui = importer.getUI();
        WorkspaceState snapshot = ui.getWorkspaceStateSnapshot();
        assertThat(snapshot.collections).isEmpty();
        assertThat(snapshot.environments).hasSize(1);
        assertThat(snapshot.activeEnvironmentId).isEqualTo(profile.id);
        assertThat(snapshot.environments.get(0).variables)
                .containsEntry("baseUrl", "https://env-only.example.test");
    }

    @Test
    void manualReaddedAuthorizationTriggersImmediateWorkspaceSaveAndClearsSuppression() throws Exception {
        WorkspaceSaveFixture fixture = newFixtureWithBearerRequest();
        fixture.writeCount.set(0);
        fixture.lastJson.set(null);

        removeHeaderRow(fixture.requestEditor, "Authorization");
        awaitWriteCount(fixture.writeCount, 1);

        headersModel(fixture.requestEditor).addRow(new Object[]{"Authorization", "Bearer tok123"});
        awaitWriteCount(fixture.writeCount, 2);

        WorkspaceState saved = WorkspaceStateJson.fromJson(fixture.lastJson.get());
        ApiRequest savedRequest = saved.collections.get(0).requests.get(0);
        assertThat(savedRequest.buildMode).isEqualTo(ApiRequest.BuildMode.MANUAL_PRESERVE);
        assertThat(savedRequest.editorMaterialized).isTrue();
        assertThat(savedRequest.suppressedAutoHeaders).doesNotContain("authorization");
        assertThat(savedRequest.headers).extracting(h -> h.key).contains("Authorization");
    }

    private static WorkspaceSaveFixture newFixtureWithBearerRequest() throws Exception {
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicReference<String> lastJson = new AtomicReference<>();
        PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        Mockito.doAnswer(inv -> {
            writeCount.incrementAndGet();
            lastJson.set(inv.getArgument(1));
            return null;
        }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        WorkspaceStateService service = new WorkspaceStateService(persistedObject);

        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, service);
        ImporterPanel ui = importer.getUI();

        ApiCollection collection = bearerCollection();
        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        ui.restoreWorkspaceState(state);
        SwingUtilities.invokeAndWait(() -> { });

        RequestEditorPanel requestEditor = requestEditor(ui);
        ApiCollection liveCollection = liveCollection(ui);
        ApiRequest liveRequest = liveCollection.requests.get(0);
        requestEditor.setCurrentCollection(liveCollection);
        requestEditor.loadRequest(liveRequest);
        SwingUtilities.invokeAndWait(() -> { });

        return new WorkspaceSaveFixture(importer, ui, requestEditor, persistedObject, writeCount, lastJson);
    }

    private static ApiCollection bearerCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "AuthTest";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Get Secret";
        request.path = "Auth/OAuth/Get Secret";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://api.example.test/secret";
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.auth.properties.put("token", "tok123");
        collection.requests.add(request);
        return collection;
    }

    private static ApiCollection liveCollection(ImporterPanel ui) throws Exception {
        Field f = ImporterPanel.class.getDeclaredField("loadedCollections");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<ApiCollection> collections = (java.util.List<ApiCollection>) f.get(ui);
        return collections.get(0);
    }

    private static RequestEditorPanel requestEditor(ImporterPanel ui) throws Exception {
        Field f = ImporterPanel.class.getDeclaredField("requestEditor");
        f.setAccessible(true);
        return (RequestEditorPanel) f.get(ui);
    }

    private static DefaultTableModel headersModel(RequestEditorPanel panel) {
        try {
            Field f = RequestEditorPanel.class.getDeclaredField("headersModel");
            f.setAccessible(true);
            return (DefaultTableModel) f.get(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static DefaultTableModel paramsModel(RequestEditorPanel panel) {
        try {
            Field f = RequestEditorPanel.class.getDeclaredField("paramsModel");
            f.setAccessible(true);
            return (DefaultTableModel) f.get(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JTextArea preScriptArea(RequestEditorPanel panel) {
        try {
            return (JTextArea) privateField(panel, "preScriptArea");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CollectionTreeNode findRequestNode(DefaultMutableTreeNode node, String requestId) {
        if (node instanceof CollectionTreeNode ctn
                && ctn.getNodeType() == CollectionTreeNode.Type.REQUEST
                && ctn.request != null
                && requestId.equals(ctn.request.id)) {
            return ctn;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode childNode) {
                CollectionTreeNode match = findRequestNode(childNode, requestId);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static int findRow(DefaultTableModel model, String key) {
        for (int i = 0; i < model.getRowCount(); i++) {
            Object value = model.getValueAt(i, 0);
            if (key.equals(value)) {
                return i;
            }
        }
        throw new AssertionError("Missing row: " + key);
    }

    private static List<String> headerRows(List<ApiRequest.Header> headers) {
        List<String> rows = new ArrayList<>();
        if (headers == null) {
            return rows;
        }
        for (ApiRequest.Header header : headers) {
            if (header != null && header.key != null && !header.key.isBlank()) {
                rows.add(header.key + "=" + (header.value != null ? header.value : ""));
            }
        }
        return rows;
    }

    private static List<String> headerRows(DefaultTableModel model) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object key = model.getValueAt(i, 0);
            Object value = model.getValueAt(i, 1);
            if (key != null && !key.toString().isBlank()) {
                rows.add(key + "=" + (value != null ? value : ""));
            }
        }
        return rows;
    }

    private static ApiRequest.Variable variable(String key, String value, String type, boolean enabled) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.type = type;
        variable.enabled = enabled;
        return variable;
    }

    private static void clickExactTransport(RequestEditorPanel editor) throws Exception {
        setExactTransportWarningProvider(editor);
        SwingUtilities.invokeAndWait(() -> {
            JPopupMenu menu = createSendDropdownMenu(editor);
            for (Component component : menu.getComponents()) {
                if (component instanceof JCheckBoxMenuItem item
                        && "Exact transport headers \u2014 Advanced".equals(item.getText())) {
                    item.doClick();
                    return;
                }
            }
            throw new AssertionError("Exact transport menu item not found");
        });
    }

    private static void setExactTransportWarningProvider(RequestEditorPanel panel) {
        try {
            Class<?> providerType = Class.forName("burp.ui.RequestEditorPanel$ExactTransportWarningProvider");
            Object provider = java.lang.reflect.Proxy.newProxyInstance(
                    RequestEditorPanel.class.getClassLoader(),
                    new Class<?>[]{providerType},
                    (proxy, method, args) -> {
                        if ("confirmEnable".equals(method.getName())) {
                            return true;
                        }
                        return null;
                    });
            Method method = RequestEditorPanel.class.getDeclaredMethod("setExactTransportWarningProviderForTests", providerType);
            method.setAccessible(true);
            method.invoke(panel, provider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JTextComponent bodyRawArea(RequestEditorPanel panel) {
        try {
            return (JTextComponent) privateField(panel, "bodyRawArea");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JPopupMenu createSendDropdownMenu(RequestEditorPanel panel) {
        try {
            Method method = RequestEditorPanel.class.getDeclaredMethod("createSendDropdownMenuForTests");
            method.setAccessible(true);
            return (JPopupMenu) method.invoke(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void removeHeaderRow(RequestEditorPanel panel, String key) throws Exception {
        DefaultTableModel model = headersModel(panel);
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < model.getRowCount(); i++) {
                String current = (String) model.getValueAt(i, 0);
                if (current != null && current.equalsIgnoreCase(key)) {
                    model.removeRow(i);
                    return;
                }
            }
        });
    }

    private static void invokeRestoreWorkspaceStateAfterUiRegistration(UniversalImporter importer) {
        try {
            Method method = UniversalImporter.class.getDeclaredMethod("restoreWorkspaceStateAfterUiRegistration");
            method.setAccessible(true);
            method.invoke(importer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitWriteCount(AtomicInteger writeCount, int expected) throws Exception {
        for (int i = 0; i < 20 && writeCount.get() < expected; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(25);
        }
        assertThat(writeCount.get()).isEqualTo(expected);
    }

    private static void invokePrivateLoadCollection(ImporterPanel ui, File file) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("loadCollection", File.class);
        method.setAccessible(true);
        method.invoke(ui, file);
    }

    private static void invokePrivateRemoveCollections(ImporterPanel ui, List<ApiCollection> targets) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("removeCollections", List.class);
        method.setAccessible(true);
        method.invoke(ui, targets);
    }

    private static Object createPendingRestore(ImporterPanel ui, WorkspaceState state) throws Exception {
        Class<?> pendingClass = Class.forName("burp.ui.ImporterPanel$PendingMainRequestTreeRestore");
        java.lang.reflect.Constructor<?> ctor = pendingClass.getDeclaredConstructor(WorkspaceState.class);
        ctor.setAccessible(true);
        return ctor.newInstance(state);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void applyWorkspaceRequestTreePathsToRequests(List<ApiCollection> collections,
                                                                 java.util.Map<String, String> requestTreePaths) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("applyWorkspaceRequestTreePathsToRequests", List.class, java.util.Map.class);
        method.setAccessible(true);
        method.invoke(null, collections, requestTreePaths);
    }

    private static String workspaceTreePathKey(String collectionName, String folderPath) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("workspaceTreePathKey", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, collectionName, folderPath);
    }

    private static String workspaceRequestTreePathKey(String collectionName, int collectionIndex, ApiRequest request, int requestIndex) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("workspaceRequestTreePathKey", String.class, int.class, ApiRequest.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, collectionName, collectionIndex, request, requestIndex);
    }

    private static WorkspaceState nestedWorkspaceState() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";

        ApiRequest request = new ApiRequest();
        request.id = "req-1";
        request.name = "Get Token";
        request.path = "Get Token";
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "https://auth.example.test/token";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        state.requestTreePaths = new java.util.LinkedHashMap<>();
        state.requestTreePaths.put(
                workspaceRequestTreePathKey("APIM", 0, state.collections.get(0).requests.get(0), 0),
                "Auth/OAuth"
        );
        return state;
    }

    private static JTree requestTree(ImporterPanel ui) throws Exception {
        Field field = ImporterPanel.class.getDeclaredField("requestTree");
        field.setAccessible(true);
        return (JTree) field.get(ui);
    }

    private static TreePath findFolderPath(JTree tree, String collectionName, String... folders) {
        if (tree == null || tree.getModel() == null || tree.getModel().getRoot() == null) {
            return null;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            Object child = root.getChildAt(i);
            if (!(child instanceof burp.ui.tree.CollectionTreeNode)) {
                continue;
            }
            burp.ui.tree.CollectionTreeNode collectionNode = (burp.ui.tree.CollectionTreeNode) child;
            if (!collectionName.equals(collectionNode.collection != null ? collectionNode.collection.name : null)) {
                continue;
            }
            return findFolderPathRecursive(new TreePath(collectionNode.getPath()), collectionNode, folders, 0);
        }
        return null;
    }

    private static TreePath findFolderPathRecursive(TreePath currentPath,
                                                    burp.ui.tree.CollectionTreeNode currentNode,
                                                    String[] folders,
                                                    int depth) {
        if (depth >= folders.length) {
            return currentPath;
        }
        String nextFolder = folders[depth];
        for (int i = 0; i < currentNode.getChildCount(); i++) {
            Object child = currentNode.getChildAt(i);
            if (!(child instanceof burp.ui.tree.CollectionTreeNode)) {
                continue;
            }
            burp.ui.tree.CollectionTreeNode folderNode = (burp.ui.tree.CollectionTreeNode) child;
            if (folderNode.getNodeType() != burp.ui.tree.CollectionTreeNode.Type.FOLDER) {
                continue;
            }
            String folderLeafName = folderNode.folderPath != null
                    ? folderNode.folderPath.substring(folderNode.folderPath.lastIndexOf('/') + 1)
                    : null;
            if (!nextFolder.equals(folderLeafName)) {
                continue;
            }
            TreePath childPath = currentPath.pathByAddingChild(folderNode);
            TreePath match = findFolderPathRecursive(childPath, folderNode, folders, depth + 1);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static WorkspaceState workspaceState(String collectionName, String environmentName) {
        ApiCollection collection = new ApiCollection();
        collection.name = collectionName;
        ApiRequest request = new ApiRequest();
        request.id = "req-" + collectionName;
        request.name = "Request " + collectionName;
        request.method = "GET";
        request.url = "https://" + collectionName + ".example.test";
        request.path = collectionName;
        request.sourceCollection = collectionName;
        collection.requests = new ArrayList<>(List.of(request));

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.id = "env-" + environmentName;
        environment.name = environmentName;
        environment.ensureDefaults();
        environment.variables.put("base_url", "https://" + environmentName.toLowerCase() + ".example.test");
        state.environments = new ArrayList<>(List.of(environment));
        state.activeEnvironmentId = environment.id;
        return state;
    }

    private static void applyWorkspaceState(UniversalImporter importer, WorkspaceState state) throws Exception {
        SwingUtilities.invokeAndWait(() -> importer.getUI().restoreWorkspaceState(state));
    }

    private static void applyWorkspaceStateWithoutAutoSave(UniversalImporter importer, WorkspaceState state) throws Exception {
        ImporterPanel ui = importer.getUI();
        Field field = ImporterPanel.class.getDeclaredField("workspaceChangeListener");
        field.setAccessible(true);
        Runnable original = (Runnable) field.get(ui);
        try {
            field.set(ui, null);
            SwingUtilities.invokeAndWait(() -> ui.restoreWorkspaceState(state));
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            field.set(ui, original);
        }
    }

    private static void awaitSuccessfulWrites(FailOncePersistedStore store, int expectedWrites) throws Exception {
        awaitCondition(() -> store.successfulWriteCount.get() >= expectedWrites,
                "workspace write count " + expectedWrites);
    }

    private static void awaitErrorCount(WorkspaceLogCapture capture, int expectedErrors) throws Exception {
        awaitCondition(() -> capture.errors.size() >= expectedErrors,
                "workspace error log count " + expectedErrors);
    }

    private static void awaitCondition(java.util.concurrent.Callable<Boolean> condition, String description) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(25L);
        }
        assertThat(condition.call()).as(description).isTrue();
    }

    private static String workspaceNameFromJson(String json) {
        WorkspaceState state = WorkspaceStateJson.fromJson(json);
        return state != null
                && state.collections != null
                && !state.collections.isEmpty()
                && state.collections.get(0) != null
                ? state.collections.get(0).name
                : null;
    }

    private static String lastSavedWorkspaceJson(UniversalImporter importer) throws Exception {
        Field field = UniversalImporter.class.getDeclaredField("lastSavedWorkspaceJson");
        field.setAccessible(true);
        return (String) field.get(importer);
    }

    private static boolean isWorkspaceSaveExecutorTerminated(UniversalImporter importer) throws Exception {
        Method method = UniversalImporter.class.getDeclaredMethod("isWorkspaceSaveExecutorTerminatedForTests");
        method.setAccessible(true);
        return (boolean) method.invoke(importer);
    }

    private record WorkspaceSaveFixture(UniversalImporter importer,
                                        ImporterPanel ui,
                                        RequestEditorPanel requestEditor,
                                        PersistedObject persistedObject,
                                        AtomicInteger writeCount,
                                        AtomicReference<String> lastJson) {
    }

    private record WorkspaceSaveFailureFixture(UniversalImporter importer,
                                               FailOncePersistedStore store,
                                               WorkspaceLogCapture logs) {
    }

    private static final class FailOncePersistedStore {
        private final PersistedObject persistedObject = Mockito.mock(PersistedObject.class);
        private final AtomicReference<String> currentValue = new AtomicReference<>();
        private final AtomicInteger successfulWriteCount = new AtomicInteger();
        private final AtomicReference<String> nextFailureMessage = new AtomicReference<>();

        private FailOncePersistedStore() {
            Mockito.when(persistedObject.getString(Mockito.anyString())).thenAnswer(inv -> currentValue.get());
            Mockito.doAnswer(inv -> {
                String failureMessage = nextFailureMessage.getAndSet(null);
                if (failureMessage != null) {
                    throw new IllegalStateException(failureMessage);
                }
                currentValue.set(inv.getArgument(1, String.class));
                successfulWriteCount.incrementAndGet();
                return null;
            }).when(persistedObject).setString(Mockito.anyString(), Mockito.anyString());
        }

        void failNextWrite(String failureMessage) {
            nextFailureMessage.set(failureMessage);
        }

        String currentValue() {
            return currentValue.get();
        }

        PersistedObject persistedObject() {
            return persistedObject;
        }
    }

    private static final class WorkspaceLogCapture {
        private final List<String> errors = new CopyOnWriteArrayList<>();
    }

    private static MontoyaApi mockApi() {
        return mockApi(null);
    }

    private static MontoyaApi mockApi(WorkspaceLogCapture capture) {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        if (capture != null) {
            var logging = api.logging();
            Mockito.doAnswer(inv -> {
                capture.errors.add(inv.getArgument(0, String.class));
                return null;
            }).when(logging).logToError(Mockito.anyString());
        }
        return api;
    }

    private static void setDebounceDelay(UniversalImporter importer, int delayMs) throws Exception {
        Field debouncedField = UniversalImporter.class.getDeclaredField("debouncedWorkspaceSave");
        debouncedField.setAccessible(true);
        DebouncedSwingAction debounced = (DebouncedSwingAction) debouncedField.get(importer);
        Field timerField = DebouncedSwingAction.class.getDeclaredField("timer");
        timerField.setAccessible(true);
        Timer timer = (Timer) timerField.get(debounced);
        timer.setInitialDelay(delayMs);
        timer.setDelay(delayMs);
    }

    private static ImporterPanel newImporterUi() throws Exception {
        MontoyaApi api = mockApi();
        UniversalImporter importer = new UniversalImporter(api, burp.utils.ScriptMode.DISABLED, new WorkspaceStateService(Mockito.mock(PersistedObject.class)));
        return importer.getUI();
    }

    private static WorkspaceSaveFailureFixture newFailureFixture() throws Exception {
        FailOncePersistedStore store = new FailOncePersistedStore();
        WorkspaceLogCapture logs = new WorkspaceLogCapture();
        UniversalImporter importer = new UniversalImporter(mockApi(logs), burp.utils.ScriptMode.DISABLED, new WorkspaceStateService(store.persistedObject()));
        setDebounceDelay(importer, 5000);
        return new WorkspaceSaveFailureFixture(importer, store, logs);
    }
}
