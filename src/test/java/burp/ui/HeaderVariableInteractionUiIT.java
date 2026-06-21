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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class HeaderVariableInteractionUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POPUP_TIMEOUT = Duration.ofSeconds(2);

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
        SwingRobotTestSupport.waitUntilOnEdt(() -> editor.getCurrentRequest() != null
                        && "Header Hover".equals(editor.getCurrentRequest().name),
                UI_TIMEOUT,
                "Header-hover request was not selected");

        SwingRobotTestSupport.clickTabbedPaneTab(editor.getTabs(), "Headers", robot);
        JTable headersTable = editor.getHeadersTableForTests();
        SwingRobotTestSupport.runOnEdt(editor::markClean);

        Rectangle apiKeyBounds = headerVariableBounds(editor, "api_key");
        PopupInteractionContext resolvedEdit = new PopupInteractionContext(
                "resolved header edit",
                panel,
                editor,
                headersTable,
                apiKeyBounds,
                "api_key",
                dev,
                qa);
        runStage(resolvedEdit, "hover", () -> hoverHeaderVariable(editor, apiKeyBounds, robot));
        JPopupMenu resolvedPopup = runStageValue(resolvedEdit, "popup visible",
                () -> waitForVariablePopup(editor, "Timed out waiting for header variable popup"));
        resolvedEdit.expectedPopup = resolvedPopup;
        runStage(resolvedEdit, "popup details", () -> {
            String tooltip = SwingRobotTestSupport.runOnEdtValue(headersTable::getToolTipText);
            assertThat(tooltip).contains("{{api_key}}").contains("Resolved");
            assertThat(tooltip.toLowerCase(Locale.ROOT)).contains("active environment");
        });
        Color resolvedColor = runStageValue(resolvedEdit, "resolved color",
                () -> headerCellColor(headersTable, "{{api_key}}"));
        assertThat(resolvedColor).isEqualTo(VariableStatusColors.resolved(headersTable));
        runStage(resolvedEdit, "move into popup", () -> {
            moveIntoPopup(headersTable, apiKeyBounds, resolvedPopup, robot);
            waitForVariablePopup(editor, "Header variable popup did not remain available while moving into it", POPUP_TIMEOUT);
        });
        resolvedEdit.buttonText = "Edit in Active Env";
        JButton editButton = runStageValue(resolvedEdit, "reacquire button",
                () -> waitForCurrentPopupButton(editor, resolvedEdit.buttonText, UI_TIMEOUT));
        runStage(resolvedEdit, "click", () -> SwingRobotTestSupport.click(editButton, robot));
        runStage(resolvedEdit, "input dialog", () -> {
            Window editDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", UI_TIMEOUT);
            JTextField input = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) editDialog).getContentPane(),
                    JTextField.class,
                    "variable-dialog-input");
            assertThat(input).isNotNull();
            SwingRobotTestSupport.selectAllAndType(input, "dev-api-key-updated", robot);
            SwingRobotTestSupport.pressEnter(robot);
        });
        runStage(resolvedEdit, "confirmation dialog", () -> {
            Window editConfirm = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", UI_TIMEOUT);
            JButton editConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) editConfirm).getContentPane(),
                    JButton.class,
                    "variable-confirm-ok");
            assertThat(editConfirmButton).isNotNull();
            SwingRobotTestSupport.click(editConfirmButton, robot);
        });
        runStage(resolvedEdit, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> "dev-api-key-updated".equals(dev.variables.get("api_key")),
                UI_TIMEOUT,
                "Resolved header variable edit did not update the active environment"));
        runStage(resolvedEdit, "resolved-view update", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> editor.getResolvedViewAreaForTests().getText().contains("X-Api-Key: dev-api-key-updated"),
                UI_TIMEOUT,
                "Resolved header view did not update"));
        runStage(resolvedEdit, "postconditions", () -> {
            assertThat(qa.variables.get("api_key")).isEqualTo("qa-api-key");
            assertHeaderValue(headersTable.getModel(), "X-Api-Key", "{{api_key}}");
            assertThat(SwingRobotTestSupport.runOnEdtValue(editor::isDirty)).isFalse();
            assertNoVisiblePopup(editor);
        });

        SwingRobotTestSupport.runOnEdt(editor::markClean);
        Rectangle userIdBounds = headerVariableBounds(editor, "user_id");
        PopupInteractionContext unresolvedCreate = new PopupInteractionContext(
                "unresolved header create",
                panel,
                editor,
                headersTable,
                userIdBounds,
                "user_id",
                dev,
                qa);
        runStage(unresolvedCreate, "hover", () -> hoverHeaderVariable(editor, userIdBounds, robot));
        JPopupMenu unresolvedPopup = runStageValue(unresolvedCreate, "popup visible",
                () -> waitForVariablePopup(editor, "Timed out waiting for header variable popup"));
        unresolvedCreate.expectedPopup = unresolvedPopup;
        runStage(unresolvedCreate, "popup details", () -> {
            String tooltip = SwingRobotTestSupport.runOnEdtValue(headersTable::getToolTipText);
            assertThat(tooltip).contains("{{user_id}}").contains("Unresolved");
        });
        Color unresolvedColor = runStageValue(unresolvedCreate, "unresolved color",
                () -> headerCellColor(headersTable, "{{user_id}}"));
        assertThat(unresolvedColor).isEqualTo(VariableStatusColors.unresolved(headersTable));
        assertThat(unresolvedColor).isNotEqualTo(resolvedColor);
        runStage(unresolvedCreate, "move into popup", () -> {
            moveIntoPopup(headersTable, userIdBounds, unresolvedPopup, robot);
            waitForVariablePopup(editor, "Header variable popup did not remain available while moving into it", POPUP_TIMEOUT);
        });
        unresolvedCreate.buttonText = "Create in Active Env";
        JButton createButton = runStageValue(unresolvedCreate, "reacquire button",
                () -> waitForCurrentPopupButton(editor, unresolvedCreate.buttonText, UI_TIMEOUT));
        runStage(unresolvedCreate, "click", () -> SwingRobotTestSupport.click(createButton, robot));
        runStage(unresolvedCreate, "input dialog", () -> {
            Window createDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", UI_TIMEOUT);
            JTextField createField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createDialog).getContentPane(),
                    JTextField.class,
                    "variable-dialog-input");
            assertThat(createField).isNotNull();
            SwingRobotTestSupport.selectAllAndType(createField, "dev-user", robot);
            SwingRobotTestSupport.pressEnter(robot);
        });
        runStage(unresolvedCreate, "confirmation dialog", () -> {
            Window createConfirm = SwingRobotTestSupport.waitForWindowTitle("Create Variable Confirmation", UI_TIMEOUT);
            JButton createConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createConfirm).getContentPane(),
                    JButton.class,
                    "variable-confirm-ok");
            assertThat(createConfirmButton).isNotNull();
            SwingRobotTestSupport.click(createConfirmButton, robot);
        });
        runStage(unresolvedCreate, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> "dev-user".equals(dev.variables.get("user_id")),
                UI_TIMEOUT,
                "Unresolved header variable create did not update the active environment"));
        runStage(unresolvedCreate, "resolved-view update", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> editor.getResolvedViewAreaForTests().getText().contains("X-User: dev-user"),
                UI_TIMEOUT,
                "Unresolved header view did not update"));
        runStage(unresolvedCreate, "postconditions", () -> {
            assertThat(qa.variables.get("user_id")).isEqualTo("qa-user");
            assertHeaderValue(headersTable.getModel(), "X-User", "{{user_id}}");
            assertThat(SwingRobotTestSupport.runOnEdtValue(editor::isDirty)).isFalse();
            assertNoVisiblePopup(editor);
        });

        Rectangle disabledBounds = headerVariableBounds(editor, "disabled_value");
        PopupInteractionContext disabledEdit = new PopupInteractionContext(
                "disabled header edit",
                panel,
                editor,
                headersTable,
                disabledBounds,
                "disabled_value",
                dev,
                qa);
        runStage(disabledEdit, "hover", () -> hoverHeaderVariable(editor, disabledBounds, robot));
        JPopupMenu disabledPopup = runStageValue(disabledEdit, "popup visible",
                () -> waitForVariablePopup(editor, "Timed out waiting for header variable popup"));
        disabledEdit.expectedPopup = disabledPopup;
        runStage(disabledEdit, "popup details", () -> {
            String tooltip = SwingRobotTestSupport.runOnEdtValue(headersTable::getToolTipText);
            assertThat(tooltip).contains("[Disabled header]");
            assertThat(findLabelContaining(disabledPopup, "Header is disabled and will be omitted from the final request.")).isNotNull();
        });
        Font disabledFont = runStageValue(disabledEdit, "disabled font",
                () -> headerCellFont(headersTable, "{{disabled_value}}"));
        Color disabledColor = runStageValue(disabledEdit, "disabled color",
                () -> headerCellColor(headersTable, "{{disabled_value}}"));
        assertThat(disabledFont.isItalic()).isTrue();
        assertThat(disabledColor).isEqualTo(VariableStatusColors.disabled(headersTable));
        assertThat(disabledColor).isNotEqualTo(resolvedColor);
        assertThat(disabledColor).isNotEqualTo(unresolvedColor);
        runStage(disabledEdit, "move into popup", () -> {
            moveIntoPopup(headersTable, disabledBounds, disabledPopup, robot);
            waitForVariablePopup(editor, "Header variable popup did not remain available while moving into it", POPUP_TIMEOUT);
        });
        disabledEdit.buttonText = "Edit in Active Env";
        JButton disabledEditButton = runStageValue(disabledEdit, "reacquire button",
                () -> waitForCurrentPopupButton(editor, disabledEdit.buttonText, UI_TIMEOUT));
        runStage(disabledEdit, "click", () -> SwingRobotTestSupport.click(disabledEditButton, robot));
        runStage(disabledEdit, "input dialog", () -> {
            Window disabledDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", UI_TIMEOUT);
            JTextField disabledField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) disabledDialog).getContentPane(),
                    JTextField.class,
                    "variable-dialog-input");
            assertThat(disabledField).isNotNull();
            SwingRobotTestSupport.selectAllAndType(disabledField, "disabled-updated", robot);
            SwingRobotTestSupport.pressEnter(robot);
        });
        runStage(disabledEdit, "confirmation dialog", () -> {
            Window disabledConfirm = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", UI_TIMEOUT);
            JButton disabledConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) disabledConfirm).getContentPane(),
                    JButton.class,
                    "variable-confirm-ok");
            assertThat(disabledConfirmButton).isNotNull();
            SwingRobotTestSupport.click(disabledConfirmButton, robot);
        });
        runStage(disabledEdit, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> "disabled-updated".equals(dev.variables.get("disabled_value")),
                UI_TIMEOUT,
                "Disabled header variable edit did not update the active environment"));
        runStage(disabledEdit, "postconditions", () -> {
            byte[] raw = buildRawRequest(editor, dev);
            String rawText = new String(raw, StandardCharsets.UTF_8);
            assertThat(rawText).doesNotContain("X-Disabled:");
            assertHeaderValue(headersTable.getModel(), "X-Disabled", "{{disabled_value}}");
            assertThat(SwingRobotTestSupport.runOnEdtValue(editor::isDirty)).isFalse();
            assertNoVisiblePopup(editor);
        });

        JComboBox<?> envCombo = panel.getWorkbenchEnvironmentComboForTests();
        SwingRobotTestSupport.click(envCombo, robot);
        robot.keyPress(KeyEvent.VK_DOWN);
        robot.keyRelease(KeyEvent.VK_DOWN);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        SwingRobotTestSupport.awaitEdt();

        SwingRobotTestSupport.waitUntilOnEdt(() -> qa.id.equals(panel.getActiveEnvironmentId()),
                UI_TIMEOUT,
                "Workbench environment selector did not switch to QA");

        PopupInteractionContext switchedContext = new PopupInteractionContext(
                "switched header hover",
                panel,
                editor,
                headersTable,
                apiKeyBounds,
                "api_key",
                dev,
                qa);
        runStage(switchedContext, "hover", () -> hoverHeaderVariable(editor, apiKeyBounds, robot));
        JPopupMenu switchedPopup = runStageValue(switchedContext, "popup visible",
                () -> waitForVariablePopup(editor, "Timed out waiting for header variable popup"));
        switchedContext.expectedPopup = switchedPopup;
        runStage(switchedContext, "popup details", () -> {
            assertThat(findLabelContaining(switchedPopup, "Value: qa-api-key")).isNotNull();
            assertThat(findLabelContaining(switchedPopup, "Active Env: QA")).isNotNull();
            String tooltip = SwingRobotTestSupport.runOnEdtValue(headersTable::getToolTipText);
            assertThat(tooltip).contains("qa-api-key");
        });
        runStage(switchedContext, "resolved-view update", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> editor.getResolvedViewAreaForTests().getText().contains("X-Api-Key: qa-api-key"),
                UI_TIMEOUT,
                "QA header view did not update"));
        runStage(switchedContext, "dismiss popup", () -> SwingRobotTestSupport.pressEscape(robot));
        runStage(switchedContext, "popup hidden", () -> assertNoVisiblePopup(editor));
        runStage(switchedContext, "dirty state", () -> assertThat(SwingRobotTestSupport.runOnEdtValue(editor::isDirty)).isFalse());

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

    private static void hoverHeaderVariable(RequestEditorPanel editor, Rectangle bounds, Robot robot) {
        JTable table = editor.getHeadersTableForTests();
        Point point = SwingRobotTestSupport.toScreenPoint(table,
                bounds.x + Math.max(6, bounds.width / 2),
                bounds.y + Math.max(6, bounds.height / 2));
        SwingRobotTestSupport.moveTo(point, robot);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor, String message) {
        return waitForVariablePopup(editor, message, UI_TIMEOUT);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor, String message, Duration timeout) {
        return SwingRobotTestSupport.waitForPopup(() -> editor.getVisibleVariablePopupForTests(), timeout, message);
    }

    private static JButton waitForCurrentPopupButton(RequestEditorPanel editor, String buttonText, Duration timeout) {
        return SwingRobotTestSupport.waitForPopupButton(
                () -> editor.getVisibleVariablePopupForTests(),
                buttonText,
                timeout,
                "Timed out waiting for popup button: " + buttonText);
    }

    private static void moveIntoPopup(JTable table, Rectangle cellBounds, JPopupMenu popup, Robot robot) {
        Point cellPoint = SwingRobotTestSupport.toScreenPoint(table,
                cellBounds.x + Math.max(6, cellBounds.width / 2),
                cellBounds.y + Math.max(6, cellBounds.height / 2));
        Point popupPoint = popupInteriorPoint(popup);
        SwingRobotTestSupport.moveBetween(cellPoint, popupPoint, 4, robot);
    }

    private static Point popupInteriorPoint(JPopupMenu popup) {
        Rectangle bounds = SwingRobotTestSupport.boundsOnScreen(popup);
        assertThat(bounds).as("Popup bounds").isNotNull();
        return new Point(bounds.x + Math.max(12, bounds.width / 3),
                bounds.y + Math.max(12, bounds.height / 3));
    }

    private static void assertNoVisiblePopup(RequestEditorPanel editor) {
        SwingRobotTestSupport.waitUntilOnEdt(() -> {
                    JPopupMenu popup = editor.getVisibleVariablePopupForTests();
                    return popup == null || !popup.isVisible();
                },
                UI_TIMEOUT,
                "Header variable popup remained visible");
    }

    private static void assertHeaderValue(TableModel model, String headerName, String expectedValue) {
        Boolean matches = SwingRobotTestSupport.runOnEdtValue(() -> {
            for (int row = 0; row < model.getRowCount(); row++) {
                Object key = model.getValueAt(row, 0);
                if (headerName.equals(key)) {
                    return expectedValue.equals(model.getValueAt(row, 1));
                }
            }
            return null;
        });
        if (matches == null) {
            throw new AssertionError("Header not found: " + headerName);
        }
        assertThat(matches).isTrue();
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

    private static void runStage(PopupInteractionContext context, String stage, ThrowingRunnable action) {
        runStageValue(context, stage, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T runStageValue(PopupInteractionContext context, String stage, ThrowingSupplier<T> action) {
        context.stage = stage;
        try {
            return action.get();
        } catch (Throwable failure) {
            throw enrichPopupFailure(context, failure);
        }
    }

    private static AssertionError enrichPopupFailure(PopupInteractionContext context, Throwable failure) {
        PopupUiEvidence evidence = SwingRobotTestSupport.runOnEdtValue(() -> collectPopupEvidence(context));
        Path screenshot = SwingRobotTestSupport.captureScreenshot(
                "HeaderVariableInteractionUiIT-" + sanitizeFileName(context.flowLabel) + "-" + sanitizeFileName(context.stage) + ".png");
        String message = context.flowLabel + " failed during " + context.stage
                + System.lineSeparator() + "expectedPopup=" + evidence.expectedPopupIdentity()
                + System.lineSeparator() + "currentPopup=" + evidence.currentPopupIdentity()
                + System.lineSeparator() + "currentPopupVisible=" + evidence.currentPopupVisible()
                + System.lineSeparator() + "currentPopupShowing=" + evidence.currentPopupShowing()
                + System.lineSeparator() + "buttonText=" + evidence.buttonText()
                + System.lineSeparator() + "buttonVisible=" + evidence.buttonVisible()
                + System.lineSeparator() + "buttonShowing=" + evidence.buttonShowing()
                + System.lineSeparator() + "buttonEnabled=" + evidence.buttonEnabled()
                + System.lineSeparator() + "sourceBounds=" + evidence.sourceBounds()
                + System.lineSeparator() + "popupBounds=" + evidence.popupBounds()
                + System.lineSeparator() + "pointer=" + evidence.pointer()
                + System.lineSeparator() + "activeEnvironmentId=" + evidence.activeEnvironmentId()
                + System.lineSeparator() + "activeEnvironmentName=" + evidence.activeEnvironmentName()
                + System.lineSeparator() + "variableKey=" + context.variableKey
                + System.lineSeparator() + "variableValue=" + evidence.variableValue()
                + System.lineSeparator() + "resolvedView=" + evidence.resolvedViewPreview()
                + System.lineSeparator() + "activeWindows=" + evidence.activeWindows()
                + System.lineSeparator() + "screenshot=" + (screenshot != null ? screenshot : "unavailable");
        return new AssertionError(message, failure);
    }

    private static PopupUiEvidence collectPopupEvidence(PopupInteractionContext context) {
        JPopupMenu currentPopup = context.editor.getVisibleVariablePopupForTests();
        JButton currentButton = currentPopup != null && context.buttonText != null
                ? SwingRobotTestSupport.findByText(currentPopup, JButton.class, context.buttonText)
                : null;
        String activeEnvironmentId = context.panel.getActiveEnvironmentId();
        EnvironmentProfile activeEnvironment = environmentById(activeEnvironmentId, context.environments);
        return new PopupUiEvidence(
                SwingRobotTestSupport.visibleWindowTitles(),
                popupIdentity(context.expectedPopup),
                popupIdentity(currentPopup),
                currentPopup != null && currentPopup.isVisible(),
                currentPopup != null && currentPopup.isShowing(),
                context.buttonText,
                currentButton != null && currentButton.isVisible(),
                currentButton != null && currentButton.isShowing(),
                currentButton != null && currentButton.isEnabled(),
                toScreenBounds(context.sourceComponent, context.sourceBounds),
                SwingRobotTestSupport.boundsOnScreen(currentPopup),
                SwingRobotTestSupport.pointerLocation(),
                activeEnvironmentId,
                activeEnvironment != null ? activeEnvironment.name : null,
                activeEnvironment != null ? activeEnvironment.variables.get(context.variableKey) : null,
                trimForEvidence(context.editor.getResolvedViewAreaForTests().getText()));
    }

    private static EnvironmentProfile environmentById(String id, EnvironmentProfile... environments) {
        if (id == null || environments == null) {
            return null;
        }
        for (EnvironmentProfile environment : environments) {
            if (environment != null && id.equals(environment.id)) {
                return environment;
            }
        }
        return null;
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

    private static String popupIdentity(JPopupMenu popup) {
        return popup != null ? popup.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(popup)) : null;
    }

    private static String sanitizeFileName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private static String trimForEvidence(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace(System.lineSeparator(), "\\n");
        return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class PopupInteractionContext {
        private final String flowLabel;
        private final ImporterPanel panel;
        private final RequestEditorPanel editor;
        private final JComponent sourceComponent;
        private final Rectangle sourceBounds;
        private final String variableKey;
        private final EnvironmentProfile[] environments;
        private JPopupMenu expectedPopup;
        private String buttonText;
        private String stage = "setup";

        private PopupInteractionContext(String flowLabel,
                                        ImporterPanel panel,
                                        RequestEditorPanel editor,
                                        JComponent sourceComponent,
                                        Rectangle sourceBounds,
                                        String variableKey,
                                        EnvironmentProfile... environments) {
            this.flowLabel = flowLabel;
            this.panel = panel;
            this.editor = editor;
            this.sourceComponent = sourceComponent;
            this.sourceBounds = sourceBounds;
            this.variableKey = variableKey;
            this.environments = environments;
        }
    }

    private record PopupUiEvidence(List<String> activeWindows,
                                   String expectedPopupIdentity,
                                   String currentPopupIdentity,
                                   boolean currentPopupVisible,
                                   boolean currentPopupShowing,
                                   String buttonText,
                                   boolean buttonVisible,
                                   boolean buttonShowing,
                                   boolean buttonEnabled,
                                   Rectangle sourceBounds,
                                   Rectangle popupBounds,
                                   Point pointer,
                                   String activeEnvironmentId,
                                   String activeEnvironmentName,
                                   String variableValue,
                                   String resolvedViewPreview) {
    }
}
