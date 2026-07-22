package burp.ui;

import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelRunnerSiteMapRetentionTest {

    @Test
    void freshUiIsSafeAndTogglePersistsExplicitValuesWithoutChangingWorkbench() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        JCheckBox runnerBox = runnerBox(bundle.panel);
        JCheckBox workbenchBox = ImporterPanelTestSupport.getField(bundle.panel, "sitemapBtn");
        AtomicInteger notifications = new AtomicInteger();
        bundle.panel.setWorkspaceChangeListener(notifications::incrementAndGet);

        assertThat(runnerBox.getText()).isEqualTo("Add responses to Burp Site map");
        assertThat(runnerBox.isSelected()).isFalse();
        assertThat(bundle.runner.isAddResponsesToSiteMap()).isFalse();
        assertThat(runnerBox.getToolTipText())
                .contains("project file", "Retries", "increase project size");

        boolean workbenchInitial = workbenchBox.isSelected();
        SwingUtilities.invokeAndWait(runnerBox::doClick);
        assertThat(bundle.runner.isAddResponsesToSiteMap()).isTrue();
        assertThat(bundle.panel.getWorkspaceStateSnapshot().runnerAddResponsesToSiteMap).isTrue();
        assertThat(workbenchBox.isSelected()).isEqualTo(workbenchInitial);

        SwingUtilities.invokeAndWait(runnerBox::doClick);
        assertThat(bundle.runner.isAddResponsesToSiteMap()).isFalse();
        assertThat(bundle.panel.getWorkspaceStateSnapshot().runnerAddResponsesToSiteMap).isFalse();
        assertThat(workbenchBox.isSelected()).isEqualTo(workbenchInitial);
        assertThat(notifications.get()).isEqualTo(2);
    }

    @Test
    void restoreTreatsMissingAndNullAsFalseAndPreservesExplicitValues() {
        ImporterPanelTestSupport.PanelBundle bundle;
        try {
            bundle = ImporterPanelTestSupport.newBundle();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (Boolean value : new Boolean[]{Boolean.TRUE, Boolean.FALSE, null}) {
            WorkspaceState state = new WorkspaceState();
            state.runnerAddResponsesToSiteMap = value;
            ImporterPanelTestSupport.invokeVoid(bundle.panel, "restoreRunnerSettings",
                    new Class<?>[]{WorkspaceState.class}, state);
            boolean expected = Boolean.TRUE.equals(value);
            assertThat(runnerBox(bundle.panel).isSelected()).isEqualTo(expected);
            assertThat(bundle.runner.isAddResponsesToSiteMap()).isEqualTo(expected);
        }
    }

    @Test
    void runnerAndWorkbenchSiteMapSelectionsAreIndependentForEveryCombination() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        JCheckBox runnerBox = runnerBox(bundle.panel);
        JCheckBox workbenchBox = ImporterPanelTestSupport.getField(bundle.panel, "sitemapBtn");

        for (boolean workbench : List.of(false, true)) {
            for (boolean runner : List.of(false, true)) {
                WorkspaceState state = new WorkspaceState();
                state.workbenchSitemapSelected = workbench;
                state.runnerAddResponsesToSiteMap = runner;
                ImporterPanelTestSupport.invokeVoid(bundle.panel, "restoreWorkbenchSettings",
                        new Class<?>[]{WorkspaceState.class}, state);
                ImporterPanelTestSupport.invokeVoid(bundle.panel, "restoreRunnerSettings",
                        new Class<?>[]{WorkspaceState.class}, state);

                assertThat(workbenchBox.isSelected()).isEqualTo(workbench);
                assertThat(runnerBox.isSelected()).isEqualTo(runner);
                assertThat(bundle.runner.isAddResponsesToSiteMap()).isEqualTo(runner);
            }
        }
    }

    @Test
    void startSynchronizesCurrentCheckboxAndRunningStatePreservesSelection() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        JCheckBox box = runnerBox(bundle.panel);
        ApiRequest request = new ApiRequest();
        request.id = "sync";
        request.name = "Sync";
        request.method = "GET";
        request.url = "https://example.test/sync";

        SwingUtilities.invokeAndWait(() -> box.setSelected(true));
        bundle.runner.setAddResponsesToSiteMap(false);
        SwingUtilities.invokeAndWait(() -> ImporterPanelTestSupport.invokeVoid(
                bundle.panel, "startRunnerExecution", new Class<?>[]{List.class, boolean.class},
                List.of(request), false));
        assertThat(bundle.runner.isAddResponsesToSiteMap()).isTrue();
        bundle.runner.cancel();

        SwingUtilities.invokeAndWait(() -> {
            ImporterPanelTestSupport.invokeVoid(bundle.panel, "setRunnerControlsRunning",
                    new Class<?>[]{boolean.class}, true);
            assertThat(box.isEnabled()).isFalse();
            assertThat(box.isSelected()).isTrue();
            ImporterPanelTestSupport.invokeVoid(bundle.panel, "setRunnerControlsRunning",
                    new Class<?>[]{boolean.class}, false);
        });
        assertThat(box.isEnabled()).isTrue();
        assertThat(box.isSelected()).isTrue();

        SwingUtilities.invokeAndWait(() -> box.setSelected(false));
        bundle.runner.setAddResponsesToSiteMap(true);
        SwingUtilities.invokeAndWait(() -> ImporterPanelTestSupport.invokeVoid(
                bundle.panel, "startRunnerExecution", new Class<?>[]{List.class, boolean.class},
                List.of(request), false));
        assertThat(bundle.runner.isAddResponsesToSiteMap()).isFalse();
        bundle.runner.cancel();
    }

    private static JCheckBox runnerBox(ImporterPanel panel) {
        return ImporterPanelTestSupport.getField(panel, "runnerAddResponsesToSiteMapCheckBox");
    }
}
