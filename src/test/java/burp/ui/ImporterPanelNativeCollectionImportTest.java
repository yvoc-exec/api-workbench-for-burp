package burp.ui;

import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
import burp.models.ApiCollection;
import burp.models.WorkspaceState;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelNativeCollectionImportTest {

    @AfterEach
    void tearDown() {
        DiagnosticStore.getInstance().setCaptureEnabled(false);
        DiagnosticStore.getInstance().clear();
    }

    @Test
    void duplicateImportedCollectionIdRegeneratesOnlyTheNewCollectionAndReportsWarning() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();

        ApiCollection existing = new ApiCollection();
        existing.id = "col-shared";
        existing.name = "Existing";
        bundle.panel.restoreWorkspaceState(WorkspaceState.fromCollections(List.of(existing)));

        ApiCollection imported = new ApiCollection();
        imported.id = "col-shared";
        imported.name = "Imported";

        ImporterPanel.DropImportResult result = new ImporterPanel.DropImportResult();
        result.importedCollections.add(imported);

        DiagnosticStore.getInstance().setCaptureEnabled(true);

        ImporterPanelTestSupport.invokeVoid(
                bundle.panel,
                "applyCollectionDropImportResult",
                new Class<?>[]{ImporterPanel.DropImportResult.class},
                result);

        ImporterPanelTestSupport.awaitEdt();

        List<ApiCollection> loadedCollections = ImporterPanelTestSupport.getField(bundle.panel, "loadedCollections");
        assertThat(loadedCollections).hasSize(2);
        assertThat(loadedCollections.get(0).id).isEqualTo("col-shared");
        assertThat(loadedCollections.get(1).id).isNotBlank().isNotEqualTo("col-shared");
        assertThat(loadedCollections.get(1).name).isEqualTo("Imported");

        assertThat(DiagnosticStore.getInstance().snapshot())
                .anySatisfy(event -> {
                    assertThat(event.operation).isEqualTo(DiagnosticOperation.IMPORT);
                    assertThat(event.severity).isEqualTo(DiagnosticSeverity.WARNING);
                    assertThat(event.message).isEqualTo("Imported collection ID collision resolved");
                    assertThat(event.details).contains("collection=Imported");
                    assertThat(event.details).contains("previousId=col-shared");
                });
    }
}
