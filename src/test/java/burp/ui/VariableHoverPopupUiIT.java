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
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.tests.enabled", matches = "true")
class VariableHoverPopupUiIT {

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
        Robot robot = SwingRobotTestSupport.newRobot();

        JTree tree = panel.getRequestTreeForTests();
        RequestEditorPanel requestEditor = panel.getRequestEditorForTests();

        selectRequest(tree, "Hover Request", robot);
        SwingRobotTestSupport.waitUntil(() -> requestEditor.getCurrentRequest() != null
                        && "Hover Request".equals(requestEditor.getCurrentRequest().name),
                Duration.ofSeconds(5),
                "Request was not loaded into the editor");

        requestEditor.markClean();
        hoverUrlVariable(requestEditor, "base_url", robot);
        JPopupMenu editPopup = waitForVariablePopup(requestEditor);

        assertThat(findLabelContaining(editPopup, "{{base_url}}")).isNotNull();
        assertThat(findLabelContaining(editPopup, "Value: https://dev.example.test")).isNotNull();
        assertThat(findLabelContainingIgnoreCase(editPopup, "Source: Active Environment")).isNotNull();
        assertThat(findLabelContaining(editPopup, "Active Env: Dev")).isNotNull();
        JButton editButton = SwingRobotTestSupport.findByText(editPopup, JButton.class, "Edit in Active Env");
        assertThat(editButton).isNotNull();
        assertThat(editButton.isEnabled()).isTrue();
        moveFromUrlTokenIntoPopup(requestEditor, "base_url", editButton, robot);
        SwingRobotTestSupport.waitUntil(editPopup::isVisible,
                Duration.ofSeconds(2),
                "Popup did not remain visible while moving into it");

        SwingRobotTestSupport.click(editButton, robot);
        Window inputDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable", Duration.ofSeconds(5));
        JTextField input = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) inputDialog).getContentPane(),
                JTextField.class,
                "variable-dialog-input");
        assertThat(input).isNotNull();
        SwingRobotTestSupport.selectAllAndType(input, "https://edited.example.test", robot);
        SwingRobotTestSupport.pressEnter(robot);

        Window confirmDialog = SwingRobotTestSupport.waitForWindowTitle("Edit Variable Confirmation", Duration.ofSeconds(5));
        JButton confirm = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) confirmDialog).getContentPane(),
                JButton.class,
                "variable-confirm-ok");
        assertThat(confirm).isNotNull();
        SwingRobotTestSupport.click(confirm, robot);

        SwingRobotTestSupport.waitUntil(() ->
                        "https://edited.example.test".equals(active.variables.get("base_url")),
                Duration.ofSeconds(5),
                "Active environment did not update after edit");
        assertThat(other.variables.get("base_url")).isEqualTo("https://qa.example.test");
        assertThat(requestEditor.getUrlField().getText()).isEqualTo("{{base_url}}/users/{{user_id}}");
        assertThat(requestEditor.getResolvedViewAreaForTests().getText()).contains("https://edited.example.test");
        assertThat(requestEditor.isDirty()).isFalse();
        assertNoVisiblePopup(requestEditor);

        requestEditor.markClean();
        hoverUrlVariable(requestEditor, "user_id", robot);
        JPopupMenu createPopup = waitForVariablePopup(requestEditor);
        assertThat(findLabelContaining(createPopup, "{{user_id}}")).isNotNull();
        assertThat(findLabelContaining(createPopup, "Status: Unresolved")).isNotNull();
        JButton createButton = SwingRobotTestSupport.findByText(createPopup, JButton.class, "Create in Active Env");
        assertThat(createButton).isNotNull();
        assertThat(createButton.isEnabled()).isTrue();
        moveFromUrlTokenIntoPopup(requestEditor, "user_id", createButton, robot);
        SwingRobotTestSupport.waitUntil(createPopup::isVisible,
                Duration.ofSeconds(2),
                "Unresolved popup did not remain visible while moving into it");

        SwingRobotTestSupport.click(createButton, robot);
        Window createInputDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", Duration.ofSeconds(5));
        JButton cancelInput = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createInputDialog).getContentPane(),
                JButton.class,
                "variable-dialog-cancel");
        assertThat(cancelInput).isNotNull();
        SwingRobotTestSupport.click(cancelInput, robot);

        SwingRobotTestSupport.waitUntil(() -> !active.variables.containsKey("user_id"),
                Duration.ofSeconds(2),
                "Cancelled create unexpectedly mutated the active environment");
        assertThat(requestEditor.isDirty()).isFalse();
        assertNoVisiblePopup(requestEditor);

        hoverUrlVariable(requestEditor, "user_id", robot);
        JPopupMenu confirmCreatePopup = waitForVariablePopup(requestEditor);
        JButton confirmCreate = SwingRobotTestSupport.findByText(confirmCreatePopup, JButton.class, "Create in Active Env");
        assertThat(confirmCreate).isNotNull();
        SwingRobotTestSupport.click(confirmCreate, robot);
        Window createDialog = SwingRobotTestSupport.waitForWindowTitle("Create Variable", Duration.ofSeconds(5));
        JTextField createField = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createDialog).getContentPane(),
                JTextField.class,
                "variable-dialog-input");
        assertThat(createField).isNotNull();
        SwingRobotTestSupport.selectAllAndType(createField, "42", robot);
        SwingRobotTestSupport.pressEnter(robot);

        Window createConfirm = SwingRobotTestSupport.waitForWindowTitle("Create Variable Confirmation", Duration.ofSeconds(5));
        JButton createConfirmButton = SwingRobotTestSupport.findByName((Container) ((RootPaneContainer) createConfirm).getContentPane(),
                JButton.class,
                "variable-confirm-ok");
        assertThat(createConfirmButton).isNotNull();
        SwingRobotTestSupport.click(createConfirmButton, robot);

        SwingRobotTestSupport.waitUntil(() -> "42".equals(active.variables.get("user_id")),
                Duration.ofSeconds(5),
                "Confirmed create did not populate the active environment");
        assertThat(other.variables).doesNotContainKey("user_id");
        assertThat(requestEditor.getUrlField().getText()).isEqualTo("{{base_url}}/users/{{user_id}}");
        assertThat(requestEditor.getResolvedViewAreaForTests().getText()).contains("/users/42");
        assertThat(requestEditor.isDirty()).isFalse();
        assertNoVisiblePopup(requestEditor);

        SwingRobotTestSupport.dispose(frame);
        assertThat(requestEditor.getVisibleVariablePopupForTests()).isNull();
    }

    private static void selectRequest(JTree tree, String requestName, Robot robot) {
        TreePath path = findRequestPath(tree, requestName);
        assertThat(path).as("Tree path for request " + requestName).isNotNull();
        SwingRobotTestSupport.clickTreePath(tree, path, robot);
    }

    private static void hoverUrlVariable(RequestEditorPanel editor, String variableKey, Robot robot) {
        Rectangle bounds = SwingRobotTestSupport.runOnEdtValue(() -> editor.getUrlVariableTokenBoundsForTests(variableKey));
        assertThat(bounds).as("URL token bounds for " + variableKey).isNotNull();
        Point point = SwingRobotTestSupport.toScreenPoint(editor.getUrlField(),
                bounds.x + Math.max(4, bounds.width / 2),
                bounds.y + Math.max(4, bounds.height / 2));
        SwingRobotTestSupport.moveTo(point, robot);
    }

    private static void moveFromUrlTokenIntoPopup(RequestEditorPanel editor, String variableKey, JButton popupButton, Robot robot) {
        Rectangle bounds = SwingRobotTestSupport.runOnEdtValue(() -> editor.getUrlVariableTokenBoundsForTests(variableKey));
        assertThat(bounds).isNotNull();
        Point tokenPoint = SwingRobotTestSupport.toScreenPoint(editor.getUrlField(),
                bounds.x + Math.max(4, bounds.width / 2),
                bounds.y + Math.max(4, bounds.height / 2));
        Point popupPoint = SwingRobotTestSupport.centerOnScreen(popupButton);
        SwingRobotTestSupport.moveBetween(tokenPoint, popupPoint, 12, robot);
    }

    private static JPopupMenu waitForVariablePopup(RequestEditorPanel editor) {
        SwingRobotTestSupport.waitUntil(() -> editor.getVisibleVariablePopupForTests() != null,
                Duration.ofSeconds(5),
                "Timed out waiting for variable popup");
        JPopupMenu popup = editor.getVisibleVariablePopupForTests();
        assertThat(popup).isNotNull();
        return popup;
    }

    private static void assertNoVisiblePopup(RequestEditorPanel editor) {
        SwingRobotTestSupport.waitUntil(() -> {
                    JPopupMenu popup = editor.getVisibleVariablePopupForTests();
                    return popup == null || !popup.isVisible();
                },
                Duration.ofSeconds(5),
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
        String expected = fragment.toLowerCase(java.util.Locale.ROOT);
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label
                    && label.getText() != null
                    && label.getText().toLowerCase(java.util.Locale.ROOT).contains(expected)) {
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
}
