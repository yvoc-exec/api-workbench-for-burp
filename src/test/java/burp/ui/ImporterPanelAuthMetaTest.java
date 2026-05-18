package burp.ui;

import burp.models.ApiRequest;
import burp.models.RunnerPreviewRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelAuthMetaTest {

    @Test
    void buildAuthMetaLineIncludesAuthTypeAndSource() {
        ApiRequest request = new ApiRequest();
        request.auth = new ApiRequest.Auth();
        request.auth.type = "bearer";
        request.authSource = "collection: Auth Demo";

        String line = ImporterPanel.buildAuthMetaLine(request);

        assertThat(line).isEqualTo("Auth: bearer (collection: Auth Demo)\n");
    }

    @Test
    void runnerPreviewMissingAuthHelperTreatsNoAuthStatusesAsMissing() {
        RunnerPreviewRow noAuth = new RunnerPreviewRow();
        noAuth.authStatus = "No auth";

        RunnerPreviewRow noAuthRequest = new RunnerPreviewRow();
        noAuthRequest.authStatus = "No auth (request)";

        RunnerPreviewRow bearer = new RunnerPreviewRow();
        bearer.authStatus = "bearer from collection: Demo";

        assertThat(ImporterPanel.isRunnerPreviewMissingAuth(noAuth)).isTrue();
        assertThat(ImporterPanel.isRunnerPreviewMissingAuth(noAuthRequest)).isTrue();
        assertThat(ImporterPanel.isRunnerPreviewMissingAuth(bearer)).isFalse();
    }
}
