package burp.utils;

import burp.exporter.EnvironmentExportFormat;
import burp.exporter.EnvironmentExportOptions;
import burp.exporter.EnvironmentExportService;
import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentWorkspacePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void workspaceSnapshotRetainsRuntimeOverlayWhileExportStripsRuntimeAndOAuthTokens() throws Exception {
        ImporterPanelTestSupport.PanelBundle bundle = ImporterPanelTestSupport.newBundle();
        ApiCollection collection = HistoryTestFixtures.sampleCollection();
        collection.runtimeVars.put("runtime_only", "visible-in-workspace");
        collection.runtimeOAuth2.put("oauth2_access_token", "runtime-oauth2-secret");
        bundle.panel.restoreWorkspaceCollections(List.of(collection));

        EnvironmentProfile profile = HistoryTestFixtures.sampleEnvironment();
        bundle.panel.replaceEnvironmentProfiles(List.of(profile));
        bundle.panel.setActiveEnvironmentId(profile.id);

        WorkspaceState snapshot = bundle.panel.getWorkspaceStateSnapshot();
        assertThat(snapshot.collections.get(0).runtimeVars).containsEntry("runtime_only", "visible-in-workspace");
        assertThat(snapshot.environments.get(0).variables).containsEntry("base_url", "https://api.example.test");

        Path output = tempDir.resolve("env.api-workbench.json");
        new EnvironmentExportService().exportEnvironment(
                profile,
                new EnvironmentExportOptions(EnvironmentExportFormat.API_WORKBENCH_JSON, output)
        );

        String exported = Files.readString(output);
        assertThat(exported).contains("base_url");
        assertThat(exported).doesNotContain("runtime_only");
        assertThat(exported).doesNotContain("runtime-oauth2-secret");
    }
}
