package burp.ui;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.testsupport.InteractiveVariableDialogProvider;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import javax.swing.table.TableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HeaderVariableInteractionUiIT {

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void headersTabVariablePopupSupportsResolvedUnresolvedDisabledAndEnvironmentSwitchFlows() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(
                        200,
                        "{\"ok\":true}",
                        "application/json")));
        ImporterPanel panel = importer.getUI();
        RequestEditorPanel editor = panel.getRequestEditorForTests();
        editor.setVariableDialogProvider(new InteractiveVariableDialogProvider());

        EnvironmentProfile dev = environment("env-dev", "Dev",
                "api_key", "dev-api-key",
                "disabled_value", "dev-disabled");
        EnvironmentProfile qa = environment("env-qa", "QA",
                "api_key", "qa-api-key",
                "disabled_value", "qa-disabled",
                "user_id", "qa-user");

        ApiCollection collection = new ApiCollection();
        collection.name = "Workspace";
        ApiRequest request = request("Header Hover");
        request.sourceCollection = collection.name;
        collection.requests.add(request);

        SwingRobotTestSupport.runOnEdt(() -> {
            panel.restoreWorkspaceCollections(List.of(collection));
            panel.replaceEnvironmentProfiles(List.of(dev, qa));
            panel.setActiveEnvironmentId(dev.id);
        });

        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "Header Variable UI");
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
        Robot robot = SwingRobotTestSupport.newRobot();

        JTree tree = panel.getRequestTreeForTests();
        selectRequest(tree, "Header Hover", robot);
        SwingRobotTestSupport.waitUntil(() -> editor.getCurrentRequest() != null
                        && "Header Hover".equals(editor.getCurrentRequest().name),
                Duration.ofSeconds(5),
                "Header-hover request was not selected");

        SwingRobotTestSupport.clickTabbedPaneTab(editor.getTabs(), "Headers", robot);
        JTable headersTable = editor.getHeadersTableForTests();
        editor.markClean();

        Rectangle apiKeyBounds = headerVariableBounds(editor, "api_key");
        hoverHeaderVariable(editor, apiKeyBounds, robot);
        JPopupMenu resolvedPopup = waitForVariablePopup(editor);
        assertThat(headersTable.getToolTipText()).contains("{{api_key}}").contains("Resolved");
        assertThat(headersTable.getToolTipText().toLowerCase(Locale.ROOT)).contains("active environment");
        JButton editButton = SwingRobotTestSupport.findByText(resolvedPopup, JButton.class, "Edit in Active Env");
        assertThat(editButton).isNotNull();
        moveIntoPopup(headersTable, apiKeyBounds, editButton, robot);
        waitForPopupWhileMovingIntoIt("Header variable popup", editor, resolvedPopup, headersTable, apiKeyBounds);
        JPopupMenu activeResolvedPopup = editor.getVisibleVariablePopupForTests();
        assertThat(activeResolvedPopup).isNotNull();
        editButton = SwingRobotTestSupport.findByText(activeResolvedPopup, JButton.class, "Edit in Active Env");
        assertThat(editButton).isNotNull();

        Color resolvedColor = headerCellColor(headersTable, "{{api_key}}");
        assertThat(resolvedColor).isEqualTo(VariableStatusColors.resolved(headersTable));
        SwingRobotTestSupport.click(editButton, robot);
        Window editDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", Duration.ofSeconds(5));
        JTextField input = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) editDialog).getContentPane(),
                JTextField.class,
                "variable-dialog-input");
        assertThat(input).isNotNull();
        SwingRobotTestSupport.selectAllAndType(input, "dev-api-key-updated", robot);
        SwingRobotTestSupport.pressEnter(robot);
        Window editConfirm = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", Duration.ofSeconds(5));
        JButton editConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) editConfirm).getContentPane(),
                JButton.class,
                "variable-confirm-ok");
        SwingRobotTestSupport.click(editConfirmButton, robot);

        SwingRobotTestSupport.waitUntil(() -> "dev-api-key-updated".equals(dev.variables.get("api_key")),
                Duration.ofSeconds(5),
                "Resolved header variable edit did not update the active environment");
        assertThat(qa.variables.get("api_key")).isEqualTo("qa-api-key");
        assertHeaderValue(headersTable.getModel(), "X-Api-Key", "{{api_key}}");
        assertThat(editor.getResolvedViewAreaForTests().getText()).contains("X-Api-Key: dev-api-key-updated");
        assertThat(editor.isDirty()).isFalse();
        assertNoVisiblePopup(editor);

        editor.markClean();
        hoverHeaderVariable(editor, "user_id", robot);
        JPopupMenu unresolvedPopup = waitForVariablePopup(editor);
        assertThat(headersTable.getToolTipText()).contains("{{user_id}}").contains("Unresolved");
        JButton createButton = SwingRobotTestSupport.findByText(unresolvedPopup, JButton.class, "Create in Active Env");
        assertThat(createButton).isNotNull();
        Color unresolvedColor = headerCellColor(headersTable, "{{user_id}}");
        assertThat(unresolvedColor).isEqualTo(VariableStatusColors.unresolved(headersTable));
        assertThat(unresolvedColor).isNotEqualTo(resolvedColor);
        SwingRobotTestSupport.click(createButton, robot);
        Window createDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", Duration.ofSeconds(5));
        JTextField createField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createDialog).getContentPane(),
                JTextField.class,
                "variable-dialog-input");
        assertThat(createField).isNotNull();
        SwingRobotTestSupport.selectAllAndType(createField, "dev-user", robot);
        SwingRobotTestSupport.pressEnter(robot);
        Window createConfirm = SwingRobotTestSupport.waitForWindowTitle("Create Variable Confirmation", Duration.ofSeconds(5));
        JButton createConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createConfirm).getContentPane(),
                JButton.class,
                "variable-confirm-ok");
        SwingRobotTestSupport.click(createConfirmButton, robot);

        SwingRobotTestSupport.waitUntil(() -> "dev-user".equals(dev.variables.get("user_id")),
                Duration.ofSeconds(5),
                "Unresolved header variable create did not update the active environment");
        assertThat(qa.variables.get("user_id")).isEqualTo("qa-user");
        assertHeaderValue(headersTable.getModel(), "X-User", "{{user_id}}");
        assertThat(editor.getResolvedViewAreaForTests().getText()).contains("X-User: dev-user");
        assertThat(editor.isDirty()).isFalse();
        assertNoVisiblePopup(editor);

        hoverHeaderVariable(editor, "disabled_value", robot);
        JPopupMenu disabledPopup = waitForVariablePopup(editor);
        assertThat(headersTable.getToolTipText()).contains("[Disabled header]");
        assertThat(findLabelContaining(disabledPopup, "Header is disabled and will be omitted from the final request.")).isNotNull();
        JButton disabledEditButton = SwingRobotTestSupport.findByText(disabledPopup, JButton.class, "Edit in Active Env");
        assertThat(disabledEditButton).isNotNull();
        Font disabledFont = headerCellFont(headersTable, "{{disabled_value}}");
        Color disabledColor = headerCellColor(headersTable, "{{disabled_value}}");
        assertThat(disabledFont.isItalic()).isTrue();
        assertThat(disabledColor).isEqualTo(VariableStatusColors.disabled(headersTable));
        assertThat(disabledColor).isNotEqualTo(resolvedColor);
        assertThat(disabledColor).isNotEqualTo(unresolvedColor);
        SwingRobotTestSupport.click(disabledEditButton, robot);
        Window disabledDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", Duration.ofSeconds(5));
        JTextField disabledField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) disabledDialog).getContentPane(),
                JTextField.class,
                "variable-dialog-input");
        SwingRobotTestSupport.selectAllAndType(disabledField, "disabled-updated", robot);
        SwingRobotTestSupport.pressEnter(robot);
        Window disabledConfirm = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", Duration.ofSeconds(5));
        JButton disabledConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) disabledConfirm).getContentPane(),
                JButton.class,
                "variable-confirm-ok");
        SwingRobotTestSupport.click(disabledConfirmButton, robot);

        SwingRobotTestSupport.waitUntil(() -> "disabled-updated".equals(dev.variables.get("disabled_value")),
                Duration.ofSeconds(5),
                "Disabled header variable edit did not update the active environment");
        byte[] raw = buildRawRequest(editor, dev);
        String rawText = new String(raw, StandardCharsets.UTF_8);
        assertThat(rawText).doesNotContain("X-Disabled:");
        assertHeaderValue(headersTable.getModel(), "X-Disabled", "{{disabled_value}}");
        assertThat(editor.isDirty()).isFalse();
        assertNoVisiblePopup(editor);

        JComboBox<?> envCombo = panel.getWorkbenchEnvironmentComboForTests();
        SwingRobotTestSupport.click(envCombo, robot);
        robot.keyPress(KeyEvent.VK_DOWN);
        robot.keyRelease(KeyEvent.VK_DOWN);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        SwingRobotTestSupport.awaitEdt();

        SwingRobotTestSupport.waitUntil(() -> qa.id.equals(panel.getActiveEnvironmentId()),
                Duration.ofSeconds(5),
                "Workbench environment selector did not switch to QA");

        hoverHeaderVariable(editor, "api_key", robot);
        JPopupMenu switchedPopup = waitForVariablePopup(editor);
        assertThat(findLabelContaining(switchedPopup, "Value: qa-api-key")).isNotNull();
        assertThat(findLabelContaining(switchedPopup, "Active Env: QA")).isNotNull();
        assertThat(headersTable.getToolTipText()).contains("qa-api-key");
        assertThat(editor.getResolvedViewAreaForTests().getText()).contains("X-Api-Key: qa-api-key");
        assertThat(editor.isDirty()).isFalse();
        SwingRobotTestSupport.pressEscape(robot);
        assertNoVisiblePopup(editor);

        SwingRobotTestSupport.dispose(frame);
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.id = "req-" + name.toLowerCase().replace(' ', '-');
        request.name = name;
        request.method = "GET";
        request.url = "https://api.example.test/users";
        request.headers = List.of(
                new ApiRequest.Header("X-Api-Key", "{{api_key}}", false),
                new ApiRequest.Header("X-User", "{{user_id}}", false),
                new ApiRequest.Header("X-Disabled", "{{disabled_value}}", true)
        );
        return request;
    }

    private static EnvironmentProfile environment(String id, String name, String... pairs) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.id = id;
        profile.name = name;
        profile.ensureDefaults();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            profile.variables.put(pairs[i], pairs[i + 1]);
        }
        return profile;
    }

    private static void selectRequest(JTree tree, String requestName, Robot robot) {
        TreePath path = findRequestPath(tree, requestName);
        assertThat(path).as("Tree path for " + requestName).isNotNull();
        SwingRobotTestSupport.clickTreePath(tree, path, robot);
    }

    private static TreePath findRequestPath(JTree tree, String requestName) {
        Object root = tree.getModel().getRoot();
        return root instanceof DefaultMutableTreeNode node
                ? findRequestPathRecursive(new TreePath(node), requestName)
                : null;
    }

    private static TreePath findRequestPathRecursive(TreePath current, String requestName) {
        Object node = current.getLastPathComponent();
        if (node instanceof burp.ui.tree.CollectionTreeNode treeNode
                && treeNode.request != null
                && requestName.equals(treeNode.request.name)) {
            return current;
        }
        if (node instanceof DefaultMutableTreeNode mutableNode) {
            for (int i = 0; i < mutableNode.getChildCount(); i++) {
                TreePath found = findRequestPathRecursive(current.pathByAddingChild(mutableNode.getChildAt(i)), requestName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Rectangle headerVariableBounds(RequestEditorPanel editor, String variableKey) {
        Rectangle bounds = SwingRobotTestSupport.runOnEdtValue(() -> editor.getHeaderVariableCellBoundsForTests(variableKey));
        assertThat(bounds).as("Header variable bounds for " + variableKey).isNotNull();
        return bounds;
    }

    private static void hoverHeaderVariable(RequestEditorPanel editor, String variableKey, Robot robot) {
        hoverHeaderVariable(editor, headerVariableBounds(editor, variableKey), robot);
    }

    private static void hoverHeaderVariable(RequestEditorPanel editor, Rectangle bounds, Robot robot) {
        JTable table = editor.getHeadersTableForTests();
        Point point = SwingRobotTestSupport.toScreenPoint(table,
                bounds.x + Math.max(6, bounds.width / 2),
                bounds.y + Math.max(6, bounds.height / 2));
        SwingRobotTestSupport.moveTo(point, robot);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor) {
        SwingRobotTestSupport.waitUntil(() -> editor.getVisibleVariablePopupForTests() != null,
                Duration.ofSeconds(5),
                "Timed out waiting for header variable popup");
        return editor.getVisibleVariablePopupForTests();
    }

    private static void moveIntoPopup(JTable table, Rectangle cellBounds, JButton popupButton, Robot robot) {
        Point cellPoint = SwingRobotTestSupport.toScreenPoint(table,
                cellBounds.x + Math.max(6, cellBounds.width / 2),
                cellBounds.y + Math.max(6, cellBounds.height / 2));
        Point popupPoint = SwingRobotTestSupport.centerOnScreen(popupButton);
        SwingRobotTestSupport.moveBetween(cellPoint, popupPoint, 4, robot);
    }

    private static void waitForPopupWhileMovingIntoIt(String label,
                                                      RequestEditorPanel editor,
                                                      JPopupMenu expectedPopup,
                                                      JComponent sourceComponent,
                                                      Rectangle sourceBounds) {
        try {
            SwingRobotTestSupport.waitUntil(() -> {
                        JPopupMenu popup = editor.getVisibleVariablePopupForTests();
                        return popup != null && popup.isShowing();
                    },
                    Duration.ofSeconds(2),
                    label + " did not remain available while moving into it");
        } catch (AssertionError failure) {
            throw enrichPopupHoverFailure(label, failure, expectedPopup, sourceComponent, sourceBounds);
        }
    }

    private static AssertionError enrichPopupHoverFailure(String label,
                                                          AssertionError failure,
                                                          JPopupMenu popup,
                                                          JComponent sourceComponent,
                                                          Rectangle sourceBounds) {
        PopupHoverEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> new PopupHoverEvidence(
                SwingRobotTestSupport.visibleWindowTitles(),
                popup != null && popup.isVisible(),
                popup != null && popup.isShowing(),
                toScreenBounds(sourceComponent, sourceBounds),
                SwingRobotTestSupport.boundsOnScreen(popup),
                SwingRobotTestSupport.pointerLocation()));
        Path screenshot = SwingRobotTestSupport.captureScreenshot("HeaderVariableInteractionUiIT-popup-hover.png");
        String message = label + " did not remain available while moving into it"
                + System.lineSeparator() + "activeWindows=" + evidence.activeWindows()
                + System.lineSeparator() + "popupVisible=" + evidence.popupVisible()
                + System.lineSeparator() + "popupShowing=" + evidence.popupShowing()
                + System.lineSeparator() + "sourceBounds=" + evidence.sourceBounds()
                + System.lineSeparator() + "popupBounds=" + evidence.popupBounds()
                + System.lineSeparator() + "pointer=" + evidence.pointer()
                + System.lineSeparator() + "screenshot=" + (screenshot != null ? screenshot : "unavailable");
        return new AssertionError(message, failure);
    }

    private static Rectangle toScreenBounds(Component component, Rectangle localBounds) {
        if (component == null || localBounds == null || !component.isShowing()) {
            return null;
        }
        try {
            Point topLeft = component.getLocationOnScreen();
            return new Rectangle(topLeft.x + localBounds.x, topLeft.y + localBounds.y, localBounds.width, localBounds.height);
        } catch (IllegalComponentStateException ignored) {
            return null;
        }
    }

    private static void assertNoVisiblePopup(RequestEditorPanel editor) {
        SwingRobotTestSupport.waitUntil(() -> {
                    JPopupMenu popup = editor.getVisibleVariablePopupForTests();
                    return popup == null || !popup.isVisible();
                },
                Duration.ofSeconds(5),
                "Header variable popup remained visible");
    }

    private static void assertHeaderValue(TableModel model, String headerName, String expectedValue) {
        for (int row = 0; row < model.getRowCount(); row++) {
            Object key = model.getValueAt(row, 0);
            if (headerName.equals(key)) {
                assertThat(model.getValueAt(row, 1)).isEqualTo(expectedValue);
                return;
            }
        }
        throw new AssertionError("Header not found: " + headerName);
    }

    private static Color headerCellColor(JTable table, String tokenText) {
        return SwingRobotTestSupport.runOnEdtValue(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int col = 0; col < table.getColumnCount(); col++) {
                    Object value = table.getValueAt(row, col);
                    if (tokenText.equals(value)) {
                        Component rendered = table.prepareRenderer(table.getCellRenderer(row, col), row, col);
                        return rendered.getForeground();
                    }
                }
            }
            return null;
        });
    }

    private static Font headerCellFont(JTable table, String tokenText) {
        return SwingRobotTestSupport.runOnEdtValue(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int col = 0; col < table.getColumnCount(); col++) {
                    Object value = table.getValueAt(row, col);
                    if (tokenText.equals(value)) {
                        Component rendered = table.prepareRenderer(table.getCellRenderer(row, col), row, col);
                        return rendered.getFont();
                    }
                }
            }
            return null;
        });
    }

    private static JLabel findLabelContaining(Container root, String fragment) {
        if (root == null) {
            return null;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && label.getText() != null && label.getText().contains(fragment)) {
                return label;
            }
            if (component instanceof Container nested) {
                JLabel found = findLabelContaining(nested, fragment);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static byte[] buildRawRequest(RequestEditorPanel editor, EnvironmentProfile environment) {
        ApiRequest built = SwingRobotTestSupport.runOnEdtValue(editor::buildRequestFromUI);
        VariableResolver resolver = new VariableResolver();
        resolver.addAll(environment.variables);
        try {
            return new RequestBuilder(null).buildRequest(built, resolver);
        } catch (Exception e) {
            throw new AssertionError("Failed to build raw request for header-variable UI assertion", e);
        }
    }

    private record PopupHoverEvidence(List<String> activeWindows,
                                      boolean popupVisible,
                                      boolean popupShowing,
                                      Rectangle sourceBounds,
                                      Rectangle popupBounds,
                                      Point pointer) {
    }
}
