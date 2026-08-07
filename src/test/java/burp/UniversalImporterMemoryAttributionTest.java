package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.models.WorkspaceState;
import burp.performance.MemoryHardeningFixtureFactory;
import burp.utils.ScriptMode;
import burp.utils.WorkspaceSaveResult;
import burp.utils.WorkspaceStateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniversalImporterMemoryAttributionTest {

    @Test
    void workspaceCoordinatorRetainsOnlyActiveAndLatestPendingWithoutPreviousJson() throws Exception {
        BlockingPersistedObject store = new BlockingPersistedObject();
        UniversalImporter importer = new UniversalImporter(
                mockApi(), ScriptMode.DISABLED, new WorkspaceStateService(store.object));
        try {
            WorkspaceState baseline = MemoryHardeningFixtureFactory.workspace(0, 0);
            importer.submitWorkspaceStateSaveForTests(baseline).get(10, TimeUnit.SECONDS);
            assertThat(importer.lastSavedWorkspaceSha256ForTests())
                    .isEqualTo(WorkspaceStateService.sha256(store.current.get()));
            assertThat(importer.lastSavedWorkspaceLengthForTests())
                    .isEqualTo(WorkspaceStateService.utf8Length(store.current.get()));

            store.block.set(true);
            List<CompletableFuture<WorkspaceSaveResult>> saves = new ArrayList<>();
            for (int revision = 0; revision < 10; revision++) {
                WorkspaceState state = MemoryHardeningFixtureFactory.workspace(1, 32 * 1024);
                state.selectedRequestName = "revision-" + revision;
                saves.add(importer.submitWorkspaceStateSaveForTests(state));
            }

            assertThat(store.blockedWriteStarted.await(5, TimeUnit.SECONDS))
                    .as("save statuses: %s", saves.stream()
                            .map(save -> save.isDone()
                                    ? save.join().status() + ":" + save.join().failureReason()
                                    : "PENDING")
                            .toList())
                    .isTrue();
            assertThat(importer.activeWorkspaceStateSaveCountForTests()).isEqualTo(1);
            assertThat(importer.pendingWorkspaceStateSaveCountForTests()).isEqualTo(1);
            assertThat(importer.maxWorkspaceStateSaveSnapshotCountForTests()).isEqualTo(2);
            assertThat(saves.subList(1, 9)).allSatisfy(save ->
                    assertThat(save.join().status()).isEqualTo(WorkspaceSaveResult.Status.SUPERSEDED));

            store.release.countDown();
            for (CompletableFuture<WorkspaceSaveResult> save : saves) {
                save.get(10, TimeUnit.SECONDS);
            }

            assertThat(store.writes.get()).isEqualTo(3);
            assertThat(importer.activeWorkspaceStateSaveCountForTests()).isZero();
            assertThat(importer.pendingWorkspaceStateSaveCountForTests()).isZero();
            assertThat(store.current.get()).contains("revision-9");
            assertThat(importer.lastSuccessfulWorkspaceRevisionForTests())
                    .isEqualTo(importer.latestRequestedWorkspaceRevisionForTests());
            assertThatThrownBy(() -> UniversalImporter.class.getDeclaredField("lastSavedWorkspaceJson"))
                    .isInstanceOf(NoSuchFieldException.class);
        } finally {
            store.release.countDown();
            importer.cleanup();
        }
    }

    private static MontoyaApi mockApi() {
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class)))
                .thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(api.userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class)))
                .thenReturn(responseEditor);
        return api;
    }

    private static final class BlockingPersistedObject {
        final PersistedObject object = Mockito.mock(PersistedObject.class);
        final AtomicBoolean block = new AtomicBoolean();
        final CountDownLatch blockedWriteStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicReference<String> current = new AtomicReference<>();

        BlockingPersistedObject() {
            Mockito.when(object.getString(Mockito.anyString())).thenAnswer(invocation -> current.get());
            Mockito.doAnswer(invocation -> {
                if (block.get()) {
                    blockedWriteStarted.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("blocked workspace write timed out");
                    }
                }
                current.set(invocation.getArgument(1, String.class));
                writes.incrementAndGet();
                return null;
            }).when(object).setString(Mockito.anyString(), Mockito.anyString());
        }
    }
}
