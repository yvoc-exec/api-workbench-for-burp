package burp.ui;

import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelWorkbenchLayoutTest {

    @Test
    void collectionsToolbarContainsActionsInOrderAndOnlyOnce() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JPanel workbenchTab = workbenchTab(panel);
        JPanel collectionsPanel = titledPanel(workbenchTab, "Collections");
        List<String> visibleButtons = directChildren(collectionsPanel, JButton.class).stream()
                .filter(AbstractButton::isVisible)
                .map(AbstractButton::getText)
                .toList();

        assertThat(visibleButtons).containsExactly("+ Add Collection", "- Remove Collection", "Actions");
        assertThat(Integer.valueOf(countButtonsByText(workbenchTab, "Actions"))).isEqualTo(1);
        assertThat(collectionsPanel).isSameAs(((JButton) field(panel, "importBtn")).getParent());
        assertThat((JButton) field(panel, "importBtn")).isSameAs((JButton) field(panel, "actionsBtn"));
        assertThat(((JButton) field(panel, "importBtn")).getText()).isEqualTo("Actions");
        assertThat(((JButton) field(panel, "importBtn")).isEnabled()).isFalse();
        assertThat((JButton) field(panel, "sendToRunnerBtn")).isNotNull();
        assertThat(findPanelsWithTitle(workbenchTab, "Options")).isEmpty();
    }

    @Test
    void leftPaneUsesBorderLayoutWithTreeCenterAndEnvironmentSouth() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JPanel workbenchTab = workbenchTab(panel);
        JSplitPane mainSplit = findComponent(workbenchTab, JSplitPane.class, split -> split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);
        JPanel leftPane = (JPanel) mainSplit.getLeftComponent();
        BorderLayout layout = (BorderLayout) leftPane.getLayout();
        JPanel collectionsPanel = titledPanel(leftPane, "Collections");
        JScrollPane requestTreeScrollPane = (JScrollPane) field(panel, "requestTreeScrollPane");
        JPanel environmentPanel = titledPanel(leftPane, "Environment");

        assertThat(layout).isNotNull();
        assertThat(layout.getLayoutComponent(leftPane, BorderLayout.NORTH)).isSameAs(collectionsPanel);
        assertThat(layout.getLayoutComponent(leftPane, BorderLayout.CENTER)).isSameAs(requestTreeScrollPane);
        assertThat(layout.getLayoutComponent(leftPane, BorderLayout.SOUTH)).isSameAs(environmentPanel);
        assertThat(leftPane.getComponentCount()).isEqualTo(3);
        assertThat(requestTreeScrollPane.getBorder()).isInstanceOf(TitledBorder.class);
        assertThat(((TitledBorder) requestTreeScrollPane.getBorder()).getTitle()).isEqualTo("Request Tree");
        assertThat(environmentPanel.getBorder()).isInstanceOf(TitledBorder.class);
        assertThat(((TitledBorder) environmentPanel.getBorder()).getTitle()).isEqualTo("Environment");
    }

    @Test
    void optionsPanelIsRemovedFromWorkbenchTree() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JPanel workbenchTab = workbenchTab(panel);

        assertThat(findPanelsWithTitle(workbenchTab, "Options")).isEmpty();
    }

    @Test
    void environmentPaneKeepsItsControls() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JPanel workbenchTab = workbenchTab(panel);
        JSplitPane mainSplit = findComponent(workbenchTab, JSplitPane.class, split -> split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);
        JPanel leftPane = (JPanel) mainSplit.getLeftComponent();
        JPanel environmentPanel = titledPanel(leftPane, "Environment");

        List<JLabel> labels = directChildren(environmentPanel, JLabel.class);
        @SuppressWarnings("rawtypes")
        List<JComboBox> combos = directChildren(environmentPanel, JComboBox.class);
        List<JButton> buttons = directChildren(environmentPanel, JButton.class);

        assertThat(environmentPanel).isSameAs(((BorderLayout) leftPane.getLayout()).getLayoutComponent(leftPane, BorderLayout.SOUTH));
        assertThat(labels).extracting(JLabel::getText).containsExactly("Active Environment:");
        assertThat(combos).hasSize(1);
        assertThat(buttons).extracting(AbstractButton::getText).containsExactly("Import");
    }

    @Test
    void requestTreeReceivesRemainingCenterSpace() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JPanel workbenchTab = workbenchTab(panel);
        JSplitPane mainSplit = findComponent(workbenchTab, JSplitPane.class, split -> split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);
        JPanel leftPane = (JPanel) mainSplit.getLeftComponent();
        JPanel collectionsPanel = titledPanel(leftPane, "Collections");
        JScrollPane requestTreeScrollPane = (JScrollPane) field(panel, "requestTreeScrollPane");
        JPanel environmentPanel = titledPanel(leftPane, "Environment");

        SwingUtilities.invokeAndWait(() -> {
            workbenchTab.setSize(480, 700);
            workbenchTab.doLayout();
            mainSplit.setSize(workbenchTab.getSize());
            mainSplit.doLayout();
            leftPane.doLayout();
        });
        ImporterPanelTestSupport.awaitEdt();

        assertThat(collectionsPanel.getY()).isLessThan(requestTreeScrollPane.getY());
        assertThat(requestTreeScrollPane.getY()).isLessThan(environmentPanel.getY());
        assertThat(requestTreeScrollPane.getWidth()).isGreaterThan(0);
        assertThat(requestTreeScrollPane.getHeight()).isGreaterThan(0);
        assertThat(requestTreeScrollPane.getHeight()).isGreaterThan(collectionsPanel.getHeight());
        assertThat(requestTreeScrollPane.getHeight()).isGreaterThan(environmentPanel.getHeight());
        assertThat(environmentPanel.getY() + environmentPanel.getHeight()).isGreaterThanOrEqualTo(leftPane.getHeight() - 1);
    }

    @Test
    void actionsFieldCompatibilityIsPreserved() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ImporterPanel panel = bundle.panel;
        ImporterPanelTestSupport.awaitEdt();

        JButton importBtn = field(panel, "importBtn");
        JButton actionsBtn = field(panel, "actionsBtn");
        JButton sendToRunnerBtn = field(panel, "sendToRunnerBtn");

        assertThat(importBtn).isNotNull();
        assertThat(actionsBtn).isNotNull();
        assertThat(importBtn).isSameAs(actionsBtn);
        assertThat(importBtn.getText()).isEqualTo("Actions");
        assertThat(sendToRunnerBtn).isNotNull();
    }

    private static JPanel workbenchTab(ImporterPanel panel) {
        JTabbedPane tabs = panel.getTabbedPane();
        int index = tabs.indexOfTab("Workbench");
        assertThat(index).isGreaterThanOrEqualTo(0);
        return (JPanel) tabs.getComponentAt(index);
    }

    private static JPanel titledPanel(Component root, String title) {
        return findComponent(root, JPanel.class, panel -> {
            if (!(panel.getBorder() instanceof TitledBorder titledBorder)) {
                return false;
            }
            return title.equals(titledBorder.getTitle()) && panel.isVisible();
        });
    }

    private static List<JPanel> findPanelsWithTitle(Component root, String title) {
        List<JPanel> panels = new ArrayList<>();
        visit(root, component -> {
            if (component instanceof JPanel panel && panel.isVisible() && panel.getBorder() instanceof TitledBorder titledBorder
                    && title.equals(titledBorder.getTitle())) {
                panels.add(panel);
            }
        });
        return panels;
    }

    private static int countButtonsByText(Component root, String text) {
        return directChildrenRecursive(root, AbstractButton.class).stream()
                .filter(button -> text.equals(button.getText()))
                .toList()
                .size();
    }

    private static <T extends Component> List<T> directChildren(Container container, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                matches.add(type.cast(child));
            }
        }
        return matches;
    }

    private static <T extends Component> List<T> directChildrenRecursive(Component root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        visit(root, component -> {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
        });
        return matches;
    }

    private static void visit(Component root, java.util.function.Consumer<Component> consumer) {
        consumer.accept(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                visit(child, consumer);
            }
        }
    }

    private static <T extends Component> T findComponent(Component root, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(root)) {
            T candidate = type.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findComponent(child, type, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to access field " + name, e);
        }
    }
}
