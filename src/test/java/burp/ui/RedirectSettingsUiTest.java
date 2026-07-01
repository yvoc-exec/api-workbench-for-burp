package burp.ui;

import burp.history.HistoryReplayRedirectMode;
import burp.testsupport.ImporterPanelTestSupport;
import burp.ui.history.HistoryActionsPanel;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectSettingsUiTest {

    @Test
    void workbenchRunnerAndHistoryReplayRedirectSettingsStayIndependent() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        RequestEditorPanel requestEditor = (RequestEditorPanel) ImporterPanelTestSupport.requestEditor(bundle.panel).raw();
        JCheckBox runnerFollowRedirectsBox = ImporterPanelTestSupport.getField(bundle.panel, "followRedirectsBox");
        HistoryActionsPanel historyActionsPanel = bundle.panel.getHistoryPanelForTests().getActionsPanel();

        assertThat(requestEditor.isFollowRedirectsSelected()).isTrue();
        assertThat(runnerFollowRedirectsBox.isSelected()).isTrue();
        assertThat(historyActionsPanel.getReplayRedirectMode()).isEqualTo(HistoryReplayRedirectMode.RECORDED);

        AtomicBoolean workbenchListenerValue = new AtomicBoolean(true);
        requestEditor.setFollowRedirectsChangeListener(workbenchListenerValue::set);
        requestEditor.setFollowRedirectsSelected(false);
        assertThat(workbenchListenerValue).isFalse();
        assertThat(runnerFollowRedirectsBox.isSelected()).isTrue();

        runnerFollowRedirectsBox.setSelected(false);
        assertThat(requestEditor.isFollowRedirectsSelected()).isFalse();

        historyActionsPanel.setReplayRedirectMode(HistoryReplayRedirectMode.ALWAYS_FOLLOW);
        assertThat(historyActionsPanel.getReplayRedirectMode()).isEqualTo(HistoryReplayRedirectMode.ALWAYS_FOLLOW);
        assertThat(requestEditor.isFollowRedirectsSelected()).isFalse();
        assertThat(runnerFollowRedirectsBox.isSelected()).isFalse();
    }
}
