package burp.ui;

import burp.UniversalImporter;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.testsupport.InteractiveVariableDialogProvider;
import burp.testsupport.SwingRobotTestSupport;
import burp.testsupport.UiWorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class VariableHoverPopupUiIT {
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POPUP_TIMEOUT = Duration.ofSeconds(2);

    @AfterEach
    void tearDown() {
        SwingRobotTestSupport.disposeTrackedWindows();
    }

    @Test
    void urlVariableHoverPopupSupportsRealCreateAndEditFlowsWithoutDirtyingAuthoredRequest() {
        UniversalImporter importer = UiWorkflowFixtures.newImporter(
                UiWorkflowFixtures.mockUiApi(request -> burp.testsupport.RunnerScriptTestFixtures.mockResponse(
                        200,
                        "{\"ok\":true}",
                        "application/json")));
        ImporterPanel panel = importer.getUI();
        RequestEditorPanel editor = panel.getRequestEditorForTests();
        editor.setVariableDialogProvider(new InteractiveVariableDialogProvider());

        EnvironmentProfile active = environment("env-dev", "Dev",
                "base_url", "https://dev.example.test");
        EnvironmentProfile other = environment("env-qa", "QA",
                "base_url", "https://qa.example.test");
        ApiCollection collection = collection("Workspace", request("Hover Request"));

        SwingRobotTestSupport.runOnEdt(() -> {
            panel.restoreWorkspaceCollections(List.of(collection));
            panel.replaceEnvironmentProfiles(List.of(active, other));
            panel.setActiveEnvironmentId(active.id);
        });

        JFrame frame = SwingRobotTestSupport.showInFrame(panel.getPanel(), "Variable Hover Popup UI");
        SwingRobotTestSupport.expandWindowToAvailableScreen(frame);
        Robot robot = SwingRobotTestSupport.newRobot();

        JTree tree = panel.getRequestTreeForTests();
        RequestEditorPanel requestEditor = panel.getRequestEditorForTests();

        selectRequest(tree, "Hover Request", robot);
        SwingRobotTestSupport.waitUntilOnEdt(() -> requestEditor.getCurrentRequest() != null
                        && "Hover Request".equals(requestEditor.getCurrentRequest().name),
                UI_TIMEOUT,
                "Request was not loaded into the editor");

        SwingRobotTestSupport.runOnEdt(requestEditor::markClean);
        Rectangle baseUrlBounds = urlVariableBounds(requestEditor, "base_url");
        PopupInteractionContext editContext = new PopupInteractionContext(
                "resolved url edit",
                panel,
                requestEditor,
                requestEditor.getUrlField(),
                baseUrlBounds,
                "base_url",
                active,
                other);
        runStage(editContext, "hover", () -> hoverUrlVariable(requestEditor, baseUrlBounds, robot));
        JPopupMenu editPopup = runStageValue(editContext, "popup visible",
                () -> waitForVariablePopup(requestEditor, "Timed out waiting for variable popup"));
        editContext.expectedPopup = editPopup;
        runStage(editContext, "popup details", () -> {
            assertThat(findLabelContaining(editPopup, "{{base_url}}")).isNotNull();
            assertThat(findLabelContaining(editPopup, "Value: https://dev.example.test")).isNotNull();
            assertThat(findLabelContainingIgnoreCase(editPopup, "Source: Active Environment")).isNotNull();
            assertThat(findLabelContaining(editPopup, "Active Env: Dev")).isNotNull();
        });
        editContext.buttonText = "Edit in Active Env";
        runStage(editContext, "move into popup", () -> {
            SwingRobotTestSupport.moveTo(popupInteriorPoint(editPopup), robot);
            waitForExpectedCurrentPopupButton(requestEditor,
                    editContext.variableKey,
                    editContext.buttonText,
                    POPUP_TIMEOUT);
        });
        JButton editButton = runStageValue(editContext, "reacquire button",
                () -> waitForExpectedCurrentPopupButton(requestEditor, editContext.variableKey, editContext.buttonText, UI_TIMEOUT));
        runStage(editContext, "click", () -> SwingRobotTestSupport.click(editButton, robot));
        runStage(editContext, "input dialog", () -> {
            Window inputDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", UI_TIMEOUT);
            JTextField input = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) inputDialog).getContentPane(),
                    JTextField.class,
                    "variable-dialog-input");
            assertThat(input).isNotNull();
            SwingRobotTestSupport.runOnEdt(() -> input.setText("https://edited.example.test"));
            assertThat(SwingRobotTestSupport.runOnEdtValue(() -> input.getText())).isEqualTo("https://edited.example.test");
            JButton ok = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) inputDialog).getContentPane(),
                    JButton.class,
                    "variable-dialog-ok");
            assertThat(ok).isNotNull();
            SwingRobotTestSupport.runOnEdt(ok::doClick);
            SwingRobotTestSupport.waitUntilOnEdt(() -> !inputDialog.isShowing(),
                    UI_TIMEOUT,
                    "Edit dialog did not close after confirmation");
        });
        runStage(editContext, "confirmation dialog", () -> {
            Window confirmDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", UI_TIMEOUT);
            JButton confirm = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) confirmDialog).getContentPane(),
                    JButton.class,
                    "variable-confirm-ok");
            assertThat(confirm).isNotNull();
            SwingRobotTestSupport.click(confirm, robot);
            SwingRobotTestSupport.waitUntilOnEdt(() -> !confirmDialog.isShowing(),
                    UI_TIMEOUT,
                    "Edit confirmation dialog did not close");
        });
        runStage(editContext, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> "https://edited.example.test".equals(active.variables.get("base_url")),
                UI_TIMEOUT,
                "Active environment did not update after edit"));
        runStage(editContext, "resolved-view update", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> requestEditor.getResolvedViewAreaForTests().getText().contains("https://edited.example.test"),
                UI_TIMEOUT,
                "Resolved URL view did not update after edit"));
        runStage(editContext, "postconditions", () -> {
            assertThat(other.variables.get("base_url")).isEqualTo("https://qa.example.test");
            assertThat(SwingRobotTestSupport.runOnEdtValue(() -> requestEditor.getUrlField().getText()))
                    .isEqualTo("{{base_url}}/users/{{user_id}}");
            assertThat(SwingRobotTestSupport.runOnEdtValue(requestEditor::isDirty)).isFalse();
            assertNoVisiblePopup(requestEditor);
        });

        SwingRobotTestSupport.runOnEdt(requestEditor::markClean);
        Rectangle userIdBounds = urlVariableBounds(requestEditor, "user_id");
        PopupInteractionContext cancelCreateContext = new PopupInteractionContext(
                "unresolved url create cancel",
                panel,
                requestEditor,
                requestEditor.getUrlField(),
                userIdBounds,
                "user_id",
                active,
                other);
        runStage(cancelCreateContext, "hover", () -> hoverUrlVariable(requestEditor, userIdBounds, robot));
        JPopupMenu createPopup = runStageValue(cancelCreateContext, "popup visible",
                () -> waitForVariablePopup(requestEditor, "Timed out waiting for variable popup"));
        cancelCreateContext.expectedPopup = createPopup;
        runStage(cancelCreateContext, "popup details", () -> {
            assertThat(findLabelContaining(createPopup, "{{user_id}}")).isNotNull();
            assertThat(findLabelContaining(createPopup, "Status: Unresolved")).isNotNull();
        });
        cancelCreateContext.buttonText = "Create in Active Env";
        runStage(cancelCreateContext, "move into popup", () -> {
            SwingRobotTestSupport.moveTo(popupInteriorPoint(createPopup), robot);
            waitForExpectedCurrentPopupButton(requestEditor,
                    cancelCreateContext.variableKey,
                    cancelCreateContext.buttonText,
                    POPUP_TIMEOUT);
        });
        JButton createButton = runStageValue(cancelCreateContext, "reacquire button",
                () -> waitForExpectedCurrentPopupButton(requestEditor, cancelCreateContext.variableKey, cancelCreateContext.buttonText, UI_TIMEOUT));
        runStage(cancelCreateContext, "click", () -> SwingRobotTestSupport.click(createButton, robot));
        runStage(cancelCreateContext, "input dialog", () -> {
            Window createInputDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", UI_TIMEOUT);
            JButton cancelInput = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createInputDialog).getContentPane(),
                    JButton.class,
                    "variable-dialog-cancel");
            assertThat(cancelInput).isNotNull();
            SwingRobotTestSupport.runOnEdt(cancelInput::doClick);
            SwingRobotTestSupport.waitUntilOnEdt(() -> !createInputDialog.isShowing(),
                    UI_TIMEOUT,
                    "Create dialog did not close after cancellation");
        });
        runStage(cancelCreateContext, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> !active.variables.containsKey("user_id"),
                Duration.ofSeconds(2),
                "Cancelled create unexpectedly mutated the active environment"));
        runStage(cancelCreateContext, "postconditions", () -> {
            assertThat(SwingRobotTestSupport.runOnEdtValue(requestEditor::isDirty)).isFalse();
            assertNoVisiblePopup(requestEditor);
        });

        PopupInteractionContext confirmCreateContext = new PopupInteractionContext(
                "unresolved url create confirm",
                panel,
                requestEditor,
                requestEditor.getUrlField(),
                userIdBounds,
                "user_id",
                active,
                other);
        confirmCreateContext.buttonText = "Create in Active Env";
        runStage(confirmCreateContext, "hover", () -> hoverUrlVariable(requestEditor, userIdBounds, robot));
        JPopupMenu confirmCreatePopup = runStageValue(confirmCreateContext, "popup visible",
                () -> waitForVariablePopup(requestEditor, "Timed out waiting for variable popup"));
        confirmCreateContext.expectedPopup = confirmCreatePopup;
        runStage(confirmCreateContext, "move into popup", () -> {
            SwingRobotTestSupport.moveTo(popupInteriorPoint(confirmCreatePopup), robot);
            waitForExpectedCurrentPopupButton(requestEditor,
                    confirmCreateContext.variableKey,
                    confirmCreateContext.buttonText,
                    POPUP_TIMEOUT);
        });
        JButton confirmCreate = runStageValue(confirmCreateContext, "reacquire button",
                () -> waitForExpectedCurrentPopupButton(requestEditor, confirmCreateContext.variableKey, confirmCreateContext.buttonText, UI_TIMEOUT));
        runStage(confirmCreateContext, "click", () -> SwingRobotTestSupport.click(confirmCreate, robot));
        runStage(confirmCreateContext, "input dialog", () -> {
            Window createDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", UI_TIMEOUT);
            JTextField createField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createDialog).getContentPane(),
                    JTextField.class,
                    "variable-dialog-input");
            assertThat(createField).isNotNull();
            SwingRobotTestSupport.runOnEdt(() -> createField.setText("42"));
            assertThat(SwingRobotTestSupport.runOnEdtValue(() -> createField.getText())).isEqualTo("42");
            JButton ok = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createDialog).getContentPane(),
                    JButton.class,
                    "variable-dialog-ok");
            assertThat(ok).isNotNull();
            SwingRobotTestSupport.runOnEdt(ok::doClick);
            SwingRobotTestSupport.waitUntilOnEdt(() -> !createDialog.isShowing(),
                    UI_TIMEOUT,
                    "Create dialog did not close after confirmation");
        });
        runStage(confirmCreateContext, "confirmation dialog", () -> {
            Window createConfirm = SwingRobotTestSupport.waitForWindowTitle("Create Variable Confirmation", UI_TIMEOUT);
            JButton createConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createConfirm).getContentPane(),
                    JButton.class,
                    "variable-confirm-ok");
            assertThat(createConfirmButton).isNotNull();
            SwingRobotTestSupport.click(createConfirmButton, robot);
            SwingRobotTestSupport.waitUntilOnEdt(() -> !createConfirm.isShowing(),
                    UI_TIMEOUT,
                    "Create confirmation dialog did not close");
        });
        runStage(confirmCreateContext, "environment mutation", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> "42".equals(active.variables.get("user_id")),
                UI_TIMEOUT,
                "Confirmed create did not populate the active environment"));
        runStage(confirmCreateContext, "resolved-view update", () -> SwingRobotTestSupport.waitUntilOnEdt(
                () -> requestEditor.getResolvedViewAreaForTests().getText().contains("/users/42"),
                UI_TIMEOUT,
                "Resolved URL view did not update after create"));
        runStage(confirmCreateContext, "postconditions", () -> {
            assertThat(other.variables).doesNotContainKey("user_id");
            assertThat(SwingRobotTestSupport.runOnEdtValue(() -> requestEditor.getUrlField().getText()))
                    .isEqualTo("{{base_url}}/users/{{user_id}}");
            assertThat(SwingRobotTestSupport.runOnEdtValue(requestEditor::isDirty)).isFalse();
            assertNoVisiblePopup(requestEditor);
        });

        SwingRobotTestSupport.dispose(frame);
        assertThat(SwingRobotTestSupport.runOnEdtValue(() -> requestEditor.getVisibleVariablePopupForTests())).isNull();
    }

    private static void selectRequest(JTree tree, String requestName, Robot robot) {
        TreePath path = findRequestPath(tree, requestName);
        assertThat(path).as("Tree path for request " + requestName).isNotNull();
        SwingRobotTestSupport.clickTreePath(tree, path, robot);
    }

    private static Rectangle urlVariableBounds(RequestEditorPanel editor, String variableKey) {
        Rectangle bounds = SwingRobotTestSupport.runOnEdtValue(() -> editor.getUrlVariableTokenBoundsForTests(variableKey));
        assertThat(bounds).as("URL token bounds for " + variableKey).isNotNull();
        return bounds;
    }

    private static void hoverUrlVariable(RequestEditorPanel editor, Rectangle bounds, Robot robot) {
        Point point = SwingRobotTestSupport.toScreenPoint(editor.getUrlField(),
                bounds.x + Math.max(4, bounds.width / 2),
                bounds.y + Math.max(4, bounds.height / 2));
        SwingRobotTestSupport.moveTo(point, robot);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor, String message) {
        return waitForVariablePopup(editor, message, UI_TIMEOUT);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor, String message, Duration timeout) {
        return SwingRobotTestSupport.waitForPopup(() -> editor.getVisibleVariablePopupForTests(), timeout, message);
    }

    private static JButton waitForExpectedCurrentPopupButton(RequestEditorPanel editor,
                                                             String variableKey,
                                                             String buttonText,
                                                             Duration timeout) {
        String expectedToken = "{{" + variableKey + "}}";
        String message = "Timed out waiting for popup button: " + buttonText + " for " + expectedToken;
        SwingRobotTestSupport.waitUntilOnEdt(() -> {
                    JPopupMenu popup = editor.getVisibleVariablePopupForTests();
                    JButton button = popup != null ? SwingRobotTestSupport.findByText(popup, JButton.class, buttonText) : null;
                    JLabel tokenLabel = popup != null ? findLabelContaining(popup, expectedToken) : null;
                    return popup != null
                            && popup.isShowing()
                            && tokenLabel != null
                            && button != null
                            && button.isShowing()
                            && button.isEnabled();
                },
                timeout,
                message);
        return SwingRobotTestSupport.runOnEdtValue(() -> {
            JPopupMenu popup = editor.getVisibleVariablePopupForTests();
            JButton button = popup != null ? SwingRobotTestSupport.findByText(popup, JButton.class, buttonText) : null;
            JLabel tokenLabel = popup != null ? findLabelContaining(popup, expectedToken) : null;
            assertThat(popup).as(message + " popup").isNotNull();
            assertThat(popup.isShowing()).as(message + " popup showing").isTrue();
            assertThat(tokenLabel).as(message + " token").isNotNull();
            assertThat(button).as(message + " button").isNotNull();
            assertThat(button.isShowing()).as(message + " button showing").isTrue();
            assertThat(button.isEnabled()).as(message + " button enabled").isTrue();
            return button;
        });
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
                "Variable popup remained visible");
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

    private static JLabel findLabelContainingIgnoreCase(Container root, String fragment) {
        if (root == null || fragment == null) {
            return null;
        }
        String expected = fragment.toLowerCase(Locale.ROOT);
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label
                    && label.getText() != null
                    && label.getText().toLowerCase(Locale.ROOT).contains(expected)) {
                return label;
            }
            if (component instanceof Container nested) {
                JLabel found = findLabelContainingIgnoreCase(nested, fragment);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private static ApiCollection collection(String name, ApiRequest request) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.requests.add(request);
        request.sourceCollection = name;
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.id = "req-" + name.toLowerCase().replace(' ', '-');
        request.name = name;
        request.method = "GET";
        request.url = "{{base_url}}/users/{{user_id}}";
        request.path = "";
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
                "VariableHoverPopupUiIT-" + sanitizeFileName(context.flowLabel) + "-" + sanitizeFileName(context.stage) + ".png");
        String message = context.flowLabel + " failed during " + context.stage
                + System.lineSeparator() + "expectedPopup=" + evidence.expectedPopupIdentity()
                + System.lineSeparator() + "currentPopup=" + evidence.currentPopupIdentity()
                + System.lineSeparator() + "currentPopupVariable=" + evidence.currentPopupVariable()
                + System.lineSeparator() + "currentPopupActions=" + evidence.currentPopupActions()
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
                popupVariableLabel(currentPopup),
                popupActionTexts(currentPopup),
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

    private static String popupVariableLabel(JPopupMenu popup) {
        JLabel label = findLabelContaining(popup, "{{");
        return label != null ? label.getText() : null;
    }

    private static List<String> popupActionTexts(Container root) {
        return SwingRobotTestSupport.findByType(root, JButton.class).stream()
                .map(AbstractButton::getText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
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
                                   String currentPopupVariable,
                                   List<String> currentPopupActions,
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
