package burp.ui;

import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
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
        int caretBefore = area.getCaretPosition();
        Point viewBefore = viewport.getViewPosition();

        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        assertThat(area.setTextCount).isEqualTo(1);
        assertThat(area.getCaretPosition()).isEqualTo(caretBefore);
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
    void rawTypingMarksDirtyButDoesNotCommitAutomatically() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nlong_variable_name=" + "x".repeat(120)));

        assertThat(area.setTextCount).isEqualTo(2);
        assertThat(collection.replaceCount).isZero();
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Unsaved changes");
    }

    @Test
    void tableEditingMarksDirtyButDoesNotCommitAutomatically() throws Exception {
        ImporterPanel panel = newPanel();
        installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");
        model.addRow(new Object[]{"draft_key", "draft_value"});

        assertThat(collection.replaceCount).isZero();
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Unsaved changes");
    }

    @Test
    void saveNowCommitsRawViewContentAndClearsDirty() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\napi_key=from-raw"));
        invokePrivateMethod(panel, "bindVarsToSelectedCollection");

        assertThat(collection.runtimeVars).containsEntry("api_key", "from-raw");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(false);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Saved to Alpha");
    }

    @Test
    void saveNowCommitsTableViewContentAndClearsDirty() throws Exception {
        ImporterPanel panel = newPanel();
        installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");
        model.setRowCount(0);
        model.addRow(new Object[]{"token", "from-table"});
        model.addRow(new Object[]{"scope", "read"});

        invokePrivateMethod(panel, "bindVarsToSelectedCollection");

        assertThat(collection.runtimeVars)
                .containsEntry("token", "from-table")
                .containsEntry("scope", "read");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(false);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Saved to Alpha");
    }

    @Test
    void ctrlSUsesSameSaveActionAsSaveNow() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nclient_id=from-shortcut"));
        JComponent editorCard = (JComponent) privateField(panel, "varsEditorCardPanel");
        Action saveAction = editorCard.getActionMap().get("saveVariablesDraft");
        assertThat(saveAction).isNotNull();
        saveAction.actionPerformed(new java.awt.event.ActionEvent(editorCard, java.awt.event.ActionEvent.ACTION_PERFORMED, "save"));

        assertThat(collection.runtimeVars).containsEntry("client_id", "from-shortcut");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(false);
    }

    @Test
    void dirtyStateAppearsAfterLocalEditAndClearsAfterSave() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nsecret=123"));

        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Unsaved changes");

        invokePrivateMethod(panel, "bindVarsToSelectedCollection");

        assertThat(privateField(panel, "variablesDirty")).isEqualTo(false);
        assertThat(((JLabel) privateField(panel, "varsAutosaveStatusLabel")).getText()).contains("Saved to Alpha");
        assertThat(collection.runtimeVars).containsEntry("secret", "123");
    }

    @Test
    void switchingCollectionsPromptsForUnsavedChangesAndHonorsSaveDiscardCancel() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection alpha = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));
        CountingCollection beta = countingCollectionWithRuntimeVars("Beta", Map.of("token", "beta-token"));

        panel.restoreWorkspaceCollections(List.of(alpha, beta));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nclient_secret=draft"));
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);

        Object previousRef = comboItem(panel, "varsCollectionCombo", 0);
        Object nextRef = comboItem(panel, "varsCollectionCombo", 1);
        Class<?> collectionRefClass = findNestedClass(panel, "CollectionRef");
        Method switchDecision = panel.getClass().getDeclaredMethod("applyVariablesCollectionSwitchDecision", collectionRefClass, collectionRefClass, int.class);
        switchDecision.setAccessible(true);

        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", true);
        selectCollection(panel, "varsCollectionCombo", 1);
        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", false);
        switchDecision.invoke(panel, previousRef, nextRef, 0);
        assertThat(alpha.runtimeVars).containsEntry("client_secret", "draft");
        assertThat(getSelectedCollectionName((JComboBox<?>) privateField(panel, "varsCollectionCombo"))).isEqualTo("Beta");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(false);

        runOnEdt(() -> area.setText(area.getText() + "\nclient_secret=discarded"));
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", true);
        selectCollection(panel, "varsCollectionCombo", 0);
        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", false);
        switchDecision.invoke(panel, nextRef, previousRef, 1);
        assertThat(beta.runtimeVars).doesNotContainKey("client_secret");
        assertThat(getSelectedCollectionName((JComboBox<?>) privateField(panel, "varsCollectionCombo"))).isEqualTo("Alpha");

        runOnEdt(() -> area.setText(area.getText() + "\nclient_secret=cancelled"));
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", true);
        selectCollection(panel, "varsCollectionCombo", 1);
        setPrivateField(panel, "suppressVariablesCollectionSelectionPrompt", false);
        switchDecision.invoke(panel, previousRef, nextRef, 2);
        assertThat(getSelectedCollectionName((JComboBox<?>) privateField(panel, "varsCollectionCombo"))).isEqualTo("Alpha");
        assertThat(((SpyTextArea) area).getText()).contains("client_secret=cancelled");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
    }

    @Test
    void switchingRawAndTableKeepsLocalDraftWithoutCommitting() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nmode=raw"));
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);

        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");
        assertThat(collection.replaceCount).isZero();
        assertThat(model.getRowCount()).isGreaterThanOrEqualTo(1);

        setButtonSelected(panel, "varsRawViewBtn", true);
        setButtonSelected(panel, "varsTableViewBtn", false);

        assertThat(collection.replaceCount).isZero();
        assertThat(((SpyTextArea) area).getText()).contains("mode=raw");
        assertThat(privateField(panel, "variablesDirty")).isEqualTo(true);
    }

    @Test
    void variablesTableMatchesRequestEditorTablePatternAndStarterRowBehavior() throws Exception {
        ImporterPanel panel = newPanel();
        installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        JTable table = (JTable) privateField(panel, "varsTable");
        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");

        assertThat(table.getSelectionModel().getSelectionMode()).isEqualTo(ListSelectionModel.SINGLE_SELECTION);
        assertThat(table.getClientProperty("terminateEditOnFocusLost")).isEqualTo(Boolean.TRUE);
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 1)).isEqualTo("");
    }

    @Test
    void variablesTableAddAndDeleteLeaveAUsableStarterRow() throws Exception {
        ImporterPanel panel = newPanel();
        installSpyEnvVarsArea(panel);
        ApiCollection collection = collectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        setButtonSelected(panel, "varsTableViewBtn", true);
        setButtonSelected(panel, "varsRawViewBtn", false);

        DefaultTableModel model = (DefaultTableModel) privateField(panel, "varsTableModel");
        JButton addButton = findButtonWithText((Container) privateField(panel, "varsEditorCardPanel"), "+");
        JButton deleteButton = findButtonWithText((Container) privateField(panel, "varsEditorCardPanel"), "-");
        JTable table = (JTable) privateField(panel, "varsTable");

        runOnEdt(addButton::doClick);
        assertThat(model.getRowCount()).isEqualTo(2);

        runOnEdt(() -> table.getSelectionModel().setSelectionInterval(1, 1));
        runOnEdt(deleteButton::doClick);

        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo("");
        assertThat(model.getValueAt(0, 1)).isEqualTo("");
    }

    @Test
    void workspaceSnapshotDoesNotPersistDirtyVariablesDraft() throws Exception {
        ImporterPanel panel = newPanel();
        SpyTextArea area = installSpyEnvVarsArea(panel);
        CountingCollection collection = countingCollectionWithRuntimeVars("Alpha", Map.of("token", "abc"));

        panel.restoreWorkspaceCollections(List.of(collection));
        selectCollection(panel, "varsCollectionCombo", 0);
        invokePrivateMethod(panel, "renderEffectiveVariablesForSelectedCollection");

        runOnEdt(() -> area.setText(area.getText() + "\nleak_test=should_not_persist"));

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();

        assertThat(collection.runtimeVars).containsEntry("token", "abc");
        assertThat(collection.runtimeVars).doesNotContainKey("leak_test");
        assertThat(snapshot.collections.get(0).runtimeVars).containsEntry("token", "abc");
        assertThat(snapshot.collections.get(0).runtimeVars).doesNotContainKey("leak_test");
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

    private static CountingCollection countingCollectionWithRuntimeVars(String name, Map<String, String> runtimeVars) {
        CountingCollection collection = new CountingCollection(name);
        collection.runtimeVars.putAll(runtimeVars);
        return collection;
    }

    private static SpyTextArea installSpyEnvVarsArea(ImporterPanel panel) throws Exception {
        SpyTextArea area = new SpyTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                fireRawEdit(panel);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                fireRawEdit(panel);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                fireRawEdit(panel);
            }
        });
        JScrollPane scrollPane = (JScrollPane) ((JPanel) privateField(panel, "varsEditorCardPanel")).getComponent(0);
        scrollPane.setViewportView(area);
        setPrivateField(panel, "envVarsArea", area);
        return area;
    }

    private static void selectCollection(ImporterPanel panel, String fieldName, int index) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, fieldName);
        combo.setSelectedIndex(index);
    }

    private static void setButtonSelected(ImporterPanel panel, String fieldName, boolean value) throws Exception {
        ((JRadioButton) privateField(panel, fieldName)).setSelected(value);
    }

    private static void expireVariablesRawEditingForTest(ImporterPanel panel) throws Exception {
        invokePrivateMethod(panel, "expireVariablesRawEditingForTests");
    }

    private static void expireVariablesTableEditingForTest(ImporterPanel panel) throws Exception {
        invokePrivateMethod(panel, "expireVariablesTableEditingForTests");
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

    private static JViewport viewportFor(JTextArea area) {
        return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
    }

    private static void runOnEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action::run);
    }

    private static void fireRawEdit(ImporterPanel panel) {
        try {
            invokePrivateMethod(panel, "handleVariablesRawDocumentEdit");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object comboItem(ImporterPanel panel, String comboFieldName, int index) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, comboFieldName);
        return combo.getItemAt(index);
    }

    private static Class<?> findNestedClass(ImporterPanel panel, String simpleName) {
        for (Class<?> nested : panel.getClass().getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        throw new IllegalStateException("Nested class not found: " + simpleName);
    }

    private static JButton findButtonWithText(Container container, String text) {
        Deque<Container> stack = new ArrayDeque<>();
        stack.push(container);
        while (!stack.isEmpty()) {
            Container current = stack.pop();
            for (Component child : current.getComponents()) {
                if (child instanceof JButton button && text.equals(button.getText())) {
                    return button;
                }
                if (child instanceof Container childContainer) {
                    stack.push(childContainer);
                }
            }
        }
        throw new IllegalStateException("Button not found: " + text);
    }

    private static String getSelectedCollectionName(JComboBox<?> combo) {
        Object selected = combo.getSelectedItem();
        return selected != null ? selected.toString() : null;
    }

    private static final class SpyTextArea extends JTextArea {
        private int setTextCount;

        @Override
        public void setText(String t) {
            setTextCount++;
            super.setText(t);
        }
    }

    private static final class CountingCollection extends ApiCollection {
        private int replaceCount;

        private CountingCollection(String name) {
            this.name = name;
        }

        @Override
        public void replaceRuntimeVars(Map<String, String> vars) {
            replaceCount++;
            super.replaceRuntimeVars(vars);
        }
    }
}
