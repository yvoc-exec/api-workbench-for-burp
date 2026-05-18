package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspacePersistenceOptions;
import burp.models.WorkspaceState;
import burp.utils.DebouncedSwingAction;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelAutosaveTest {

    @Test
    void workspaceStatePreservesCollectionRequestMappingData() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        ApiRequest request = new ApiRequest();
        request.name = "Get Me";
        request.url = "https://api.example.test/me";
        request.sourceCollection = "Demo";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection), WorkspacePersistenceOptions.defaults());
        WorkspaceState parsed = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));

        assertThat(parsed.collections).hasSize(1);
        assertThat(parsed.collections.get(0).requests).hasSize(1);
        assertThat(parsed.collections.get(0).requests.get(0).name).isEqualTo("Get Me");
        assertThat(parsed.collections.get(0).requests.get(0).sourceCollection).isEqualTo("Demo");
    }

    @Test
    void autosaveReplaceModeClearsRemovedRuntimeVariableKeys() {
        ApiCollection collection = new ApiCollection();
        collection.runtimeVars.put("stale", "old");

        collection.replaceRuntimeVars(Map.of("fresh", "new"));

        assertThat(collection.runtimeVars).doesNotContainKey("stale");
        assertThat(collection.runtimeVars).containsEntry("fresh", "new");
    }

    @Test
    void clearVariablesEditorOnlyStopsPendingAutosaveAndDoesNotReschedule() {
        JTextArea area = new JTextArea("token=abc");
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        model.addRow(new Object[]{"token", "abc"});

        AtomicBoolean suppress = new AtomicBoolean(false);
        AtomicInteger restartCount = new AtomicInteger(0);
        AtomicInteger stopCount = new AtomicInteger(0);
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicReference<String> baseLayer = new AtomicReference<>("base layer");

        DebouncedSwingAction autosave = new DebouncedSwingAction(50, null) {
            @Override
            public void restart() {
                restartCount.incrementAndGet();
            }

            @Override
            public void stop() {
                stopCount.incrementAndGet();
            }
        };

        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!suppress.get()) {
                    autosave.restart();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!suppress.get()) {
                    autosave.restart();
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!suppress.get()) {
                    autosave.restart();
                }
            }
        });

        ImporterPanel.clearVariablesEditorOnly(
                area,
                model,
                autosave,
                () -> suppress.set(true),
                () -> suppress.set(false),
                () -> baseLayer.set(""),
                status::set
        );

        assertThat(stopCount.get()).isEqualTo(1);
        assertThat(restartCount.get()).isZero();
        assertThat(area.getText()).isEmpty();
        assertThat(model.getRowCount()).isZero();
        assertThat(baseLayer.get()).isEmpty();
        assertThat(suppress.get()).isFalse();
        assertThat(status.get()).isEqualTo("Editor cleared. Click Save Now to update the selected collection.");
    }
}
