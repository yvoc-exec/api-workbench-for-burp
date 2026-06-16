package burp.ui;

import burp.history.HistoryEntry;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryReplaysCollectionTest {

    @Test
    void missingOriginalRequestCreatesHistoryReplaysCollectionFallback() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        bundle.panel.restoreWorkspaceState(new WorkspaceState());

        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "missing", Instant.parse("2026-06-15T01:45:00Z"));
        entry.collectionName = "Missing Collection";
        entry.collectionId = "missing-collection";
        entry.requestId = "missing-request";
        entry.requestName = "Fallback Request";
        entry.folderPath = "Fallback";

        Object context = ImporterPanelTestSupport.invoke(
                bundle.panel,
                "resolveHistoryRequestContext",
                new Class<?>[]{HistoryEntry.class, boolean.class},
                entry,
                true);

        assertThat(context).isNotNull();
        assertThat((Boolean) ImporterPanelTestSupport.getField(context, "originalRequestExists")).isFalse();
        assertThat(((burp.models.ApiCollection) ImporterPanelTestSupport.getField(context, "collection")).name)
                .isEqualTo("History Replays");
        assertThat(((burp.models.ApiRequest) ImporterPanelTestSupport.getField(context, "request")).name)
                .isEqualTo("Fallback Request");
        assertThat(bundle.panel.getWorkspaceStateSnapshot().collections)
                .extracting(collection -> collection.name)
                .contains("History Replays");
    }
}
