package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import burp.auth.OAuth2Manager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelVariablesTabTest {

    @Test
    void renderEffectiveVariablesForSelectedCollectionSkipsUnchangedRewrite() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc", "base_url", "https://alpha.test"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);

        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");
        assertThat(area.setTextCount).isEqualTo(1);

        area.setCaretPosition(Math.min(5, area.getText().length()));
        JViewport viewport = viewportFor(area);
        viewport.setViewPosition(new Point(0, 42));
        Point caretBefore = new Point(area.getCaretPosition(), area.getCaretPosition());
        Point viewBefore = viewport.getViewPosition();

        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        assertThat(area.setTextCount).isEqualTo(1);
        assertThat(area.getCaretPosition()).isEqualTo(caretBefore.x);
        assertThat(viewport.getViewPosition()).isEqualTo(viewBefore);
    }

    @Test
    void renderEffectiveVariablesForSelectedCollectionPreservesCaretAndViewportWhenContentChanges() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of(
                "token", "abc",
                "base_url", "https://alpha.test",
                "scope", "read"
        ));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        area.setCaretPosition(Math.min(12, area.getText().length()));
        JViewport viewport = viewportFor(area);
        viewport.setViewPosition(new Point(0, 38));
        int caretBefore = area.getCaretPosition();
        Point viewBefore = viewport.getViewPosition();

        collection.runtimeVars.put("token", "changed-token");
        invokePrivateMethod(panel, "refreshRuntimeViewsForCollection", new Class<?>[]{ApiCollection.class}, collection);

        assertThat(area.setTextCount).isEqualTo(2);
        assertThat(area.getCaretPosition()).isEqualTo(Math.min(caretBefore, area.getText().length()));
        assertThat(viewport.getViewPosition()).isEqualTo(viewBefore);
        assertThat(area.getText()).contains("changed-token");
    }

    @Test
    void syncRawFromVarsTableSkipsRewriteWhenRenderedOutputMatchesCurrentText() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc", "base_url", "https://alpha.test"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");
        invokePrivateMethod(panel, "renderVarsTableFromRaw");

        area.setCaretPosition(Math.min(8, area.getText().length()));
        JViewport viewport = viewportFor(area);
        viewport.setViewPosition(new Point(0, 36));
        int caretBefore = area.getCaretPosition();
        Point viewBefore = viewport.getViewPosition();

        invokePrivateMethod(panel, "syncRawFromVarsTable");

        assertThat(area.setTextCount).isEqualTo(1);
        assertThat(area.getCaretPosition()).isEqualTo(caretBefore);
        assertThat(viewport.getViewPosition()).isEqualTo(viewBefore);
    }

    @Test
    void refreshRuntimeViewsForCollectionUpdatesRawEditorWhenVariablesActuallyChange() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        collection.runtimeVars.put("token", "updated-token");
        invokePrivateMethod(panel, "refreshRuntimeViewsForCollection", new Class<?>[]{ApiCollection.class}, collection);

        assertThat(area.setTextCount).isEqualTo(2);
        assertThat(area.getText()).contains("updated-token");
    }

    @Test
    void autosaveVariablesToSelectedCollectionStillPersistsTableEdits() throws Exception {
        ImporterPanel panel = newPanel();
        installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");
        model.setRowCount(0);
        model.addRow(new Object[]{"token", "from-table"});
        model.addRow(new Object[]{"scope", "read"});

        invokePrivateMethod(panel, "autosaveVariablesToSelectedCollection");

        assertThat(collection.runtimeVars)
                .containsEntry("token", "from-table")
                .containsEntry("scope", "read");
    }

    private static ImporterPanel newPanel() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        burp.api.montoya.ui.editor.HttpRequestEditor requestEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        burp.api.montoya.ui.editor.HttpResponseEditor responseEditor = Mockito.mock(burp.api.montoya.ui.editor.HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static ApiCollection collectionWithRuntimeVars(String name, Map<String, String> runtimeVars) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.runtimeVars.putAll(runtimeVars);
        return collection;
    }

    private static SpyTextArea installSpyEnvVarsArea(ImporterPanel panel) throws Exception {
        SpyTextArea area = new SpyTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = (JScrollPane) ((JPanel) privateField(panel, "varsEditorCardPanel")).getComponent(0);
        scrollPane.setViewportView(area);
        setPrivateField(panel, "envVarsArea", area);
        return area;
    }

    private static void selectCollection(ImporterPanel panel, String fieldName, int index) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, fieldName);
        combo.setSelectedIndex(index);
    }

    private static JViewport viewportFor(JTextArea area) {
        return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
    }

    private static void setButtonSelected(ImporterPanel panel, String fieldName, boolean value) throws Exception {
        ((JRadioButton) privateField(panel, fieldName)).setSelected(value);
    }

    private static void invokePrivateMethod(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void invokePrivateMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class SpyTextArea extends JTextArea {
        private int setTextCount;

        @Override
        public void setText(String t) {
            setTextCount++;
            super.setText(t);
        }
    }
}
