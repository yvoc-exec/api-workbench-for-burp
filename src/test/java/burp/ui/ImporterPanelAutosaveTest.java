package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelAutosaveTest {

    @Test
    void importerPanelNoLongerKeepsVariablesAutosaveTimerField() {
        boolean hasAutosaveField = false;
        for (Field field : ImporterPanel.class.getDeclaredFields()) {
            if ("variablesAutosave".equals(field.getName())) {
                hasAutosaveField = true;
                break;
            }
        }

        assertThat(hasAutosaveField).isFalse();
    }

    @Test
    void workspaceStatePreservesCollectionRequestMappingData() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Demo";
        ApiRequest request = new ApiRequest();
        request.name = "Get Me";
        request.url = "https://api.example.test/me";
        request.sourceCollection = "Demo";
        collection.requests.add(request);

        WorkspaceState state = WorkspaceState.fromCollections(List.of(collection));
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
    void silentRuntimeVarReplacementUpdatesWithoutFiringListeners() {
        ApiCollection collection = new ApiCollection();
        collection.runtimeVars = new LinkedHashMap<>();
        AtomicInteger changeCount = new AtomicInteger(0);
        collection.addChangeListener(changeCount::incrementAndGet);

        Map<String, String> parsed = Map.of("baseUrl", "https://api.example.test", "api_key", "secret");
        ImporterPanel.silentlyReplaceRuntimeVars(collection, parsed);

        assertThat(collection.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(collection.runtimeVars).containsEntry("api_key", "secret");
        assertThat(changeCount.get()).isZero();

        ImporterPanel.silentlyReplaceRuntimeVars(collection, parsed);

        assertThat(collection.runtimeVars).containsEntry("baseUrl", "https://api.example.test");
        assertThat(collection.runtimeVars).containsEntry("api_key", "secret");
        assertThat(changeCount.get()).isZero();
    }

    @Test
    void clearVariablesEditorOnlyClearsEditorStateWithoutAutosaveDependency() {
        JTextArea area = new JTextArea("token=abc");
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
        model.addRow(new Object[]{"token", "abc"});

        AtomicBoolean suppress = new AtomicBoolean(false);
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicReference<String> baseLayer = new AtomicReference<>("base layer");

        ImporterPanel.clearVariablesEditorOnly(
                area,
                model,
                () -> suppress.set(true),
                () -> suppress.set(false),
                () -> baseLayer.set(""),
                status::set
        );

        assertThat(area.getText()).isEmpty();
        assertThat(model.getRowCount()).isZero();
        assertThat(baseLayer.get()).isEmpty();
        assertThat(suppress.get()).isFalse();
        assertThat(status.get()).isEqualTo("Editor cleared. Click Save to update the selected environment.");
    }
}
