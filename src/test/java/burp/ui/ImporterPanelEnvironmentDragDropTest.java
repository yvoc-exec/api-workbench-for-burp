package burp.ui;

import burp.UniversalImporter;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.auth.OAuth2Manager;
import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.runner.CollectionRunner;
import burp.ui.dnd.EnvironmentDragPayload;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelEnvironmentDragDropTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedEnvironmentFiles")
    void supportedEnvironmentFormatsImportProfiles(String label,
                                                   Path file,
                                                   String expectedName,
                                                   String expectedKey,
                                                   String expectedValue) throws Exception {
        ImporterPanel panel = newPanel();

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).hasSize(1);
        EnvironmentProfile profile = panel.getEnvironmentProfilesSnapshot().get(0);
        assertThat(profile.name).isEqualTo(expectedName);
        assertThat(profile.variables).containsEntry(expectedKey, expectedValue);
        assertThat(panel.getActiveEnvironmentId()).isNull();

        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "environmentCombo");
        assertThat(combo.getItemCount()).isEqualTo(2);
        assertThat(combo.getSelectedItem()).hasToString(expectedName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedEnvironmentFiles")
    void supportedEnvironmentFormatsPersistThroughWorkspaceSnapshot(String label,
                                                                    Path file,
                                                                    String expectedName,
                                                                    String expectedKey,
                                                                    String expectedValue) throws Exception {
        ImporterPanel panel = newPanel();

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        WorkspaceState snapshot = panel.getWorkspaceStateSnapshot();
        ImporterPanel restored = newPanel();
        restored.restoreWorkspaceState(snapshot);
        drainEdt();

        assertThat(restored.getEnvironmentProfilesSnapshot()).hasSize(1);
        assertThat(restored.getEnvironmentProfilesSnapshot().get(0).name).isEqualTo(expectedName);
        assertThat(restored.getEnvironmentProfilesSnapshot().get(0).variables)
                .containsEntry(expectedKey, expectedValue);
    }

    @org.junit.jupiter.api.Test
    void dropMultipleEnvironmentFilesImportsValidAndSkipsInvalid() throws Exception {
        ImporterPanel panel = newPanel();
        Path valid = tempFile("uat.env", "base_url=https://uat.example.test\ntoken=uat-token\n");
        Path invalid = tempFile("broken.txt", "not an environment");

        panel.importEnvironmentFilesDropped(List.of(valid.toFile(), invalid.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).hasSize(1);
        assertThat(panel.getEnvironmentProfilesSnapshot().get(0).variables)
                .containsEntry("base_url", "https://uat.example.test")
                .containsEntry("token", "uat-token");
        assertThat(importLog(panel).getText()).contains("Environment drop import complete: 1 imported, 1 failed.");
    }

    @org.junit.jupiter.api.Test
    void dropUnsupportedEnvironmentFileRejectsSafely() throws Exception {
        ImporterPanel panel = newPanel();
        Path file = tempFile("unsupported.txt", "still not an environment");

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).isEmpty();
        assertThat(importLog(panel).getText()).contains("Skipped unsupported environment file");
    }

    @org.junit.jupiter.api.Test
    void dropCollectionFileOntoEnvironmentTabIsRejectedSafely() throws Exception {
        ImporterPanel panel = newPanel();
        Path file = tempFile("collection.json", """
                {
                  "info": { "name": "Checkout" },
                  "item": []
                }
                """);

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).isEmpty();
        assertThat(importLog(panel).getText()).contains("Skipped unsupported environment file");
    }

    @org.junit.jupiter.api.Test
    void dropImportDoesNotChangeCurrentActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("Active", "https://active.example.test");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        Path file = tempFile("uat.json", """
                {
                  "name": "UAT",
                  "variables": {
                    "base_url": "https://uat.example.test"
                  }
                }
                """);

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        assertThat(panel.getActiveEnvironmentId()).isEqualTo(active.id);
        assertThat(panel.getEnvironmentProfilesSnapshot()).extracting(profile -> profile.name)
                .containsExactly("Active", "UAT");
    }

    @org.junit.jupiter.api.Test
    void duplicateImportedEnvironmentNamesUseCopySuffix() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile first = environment("UAT", "https://uat.example.test");
        EnvironmentProfile copy = environment("UAT Copy", "https://uat-copy.example.test");
        panel.replaceEnvironmentProfiles(List.of(first, copy));

        Path file = tempFile("UAT.json", """
                {
                  "name": "UAT",
                  "variables": {
                    "base_url": "https://uat-2.example.test"
                  }
                }
                """);

        panel.importEnvironmentFilesDropped(List.of(file.toFile()));
        drainEdt();

        assertThat(panel.getEnvironmentProfilesSnapshot()).extracting(profile -> profile.name)
                .containsExactly("UAT", "UAT Copy", "UAT Copy 2");
    }

    @org.junit.jupiter.api.Test
    void activeEnvironmentPayloadDropSetsActiveEnvironment() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile uat = environment("UAT", "https://uat.example.test");
        EnvironmentProfile prd = environment("PRD", "https://prd.example.test");
        panel.replaceEnvironmentProfiles(List.of(uat, prd));

        boolean accepted = panel.activateEnvironmentFromDrop(new EnvironmentDragPayload(prd.id, prd.displayName()));
        drainEdt();

        assertThat(accepted).isTrue();
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(prd.id);
        JComboBox<?> combo = (JComboBox<?>) privateField(panel, "environmentCombo");
        assertThat(combo.getSelectedItem()).hasToString("PRD");
    }

    @org.junit.jupiter.api.Test
    void unknownActiveEnvironmentPayloadIsRejectedWithoutMutatingVariables() throws Exception {
        ImporterPanel panel = newPanel();
        EnvironmentProfile active = environment("Active", "https://active.example.test");
        active.variables.put("token", "abc123");
        panel.replaceEnvironmentProfiles(List.of(active));
        panel.setActiveEnvironmentId(active.id);

        Map<String, String> before = panel.getEnvironmentProfilesSnapshot().get(0).variables;
        boolean accepted = panel.activateEnvironmentFromDrop(new EnvironmentDragPayload("missing-id", "Missing"));
        drainEdt();

        assertThat(accepted).isFalse();
        assertThat(panel.getActiveEnvironmentId()).isEqualTo(active.id);
        assertThat(panel.getEnvironmentProfilesSnapshot().get(0).variables).isEqualTo(before);
        assertThat(importLog(panel).getText()).contains("Active environment drop rejected: environment not found.");
    }

    private static Stream<Arguments> supportedEnvironmentFiles() throws IOException {
        return Stream.of(
                Arguments.of("api-workbench", tempFile("api-workbench.json", """
                        {
                          "name": "UAT",
                          "variables": {
                            "base_url": "https://uat.example.test",
                            "token": "uat-token"
                          }
                        }
                        """), "UAT", "base_url", "https://uat.example.test"),
                Arguments.of("postman", tempFile("postman-env.json", """
                        {
                          "name": "UAT",
                          "values": [
                            {"key": "base_url", "value": "https://uat.example.test", "enabled": true}
                          ]
                        }
                        """), "UAT", "base_url", "https://uat.example.test"),
                Arguments.of("wrapped-postman", tempFile("wrapped-postman.json", """
                        {
                          "name": "Wrapped",
                          "environment": {
                            "name": "UAT",
                            "values": [
                              {"key": "base_url", "value": "https://uat.example.test", "enabled": true}
                            ]
                          }
                        }
                        """), "UAT", "base_url", "https://uat.example.test"),
                Arguments.of("generic-json", tempFile("generic-env.json", """
                        {
                          "base_url": "https://dev.example.test",
                          "token": "dev-token"
                        }
                        """), "generic-env", "base_url", "https://dev.example.test"),
                Arguments.of("dotenv", tempFile("qa.env", """
                        base_url=https://qa.example.test
                        token=qa-token
                        """), "qa", "base_url", "https://qa.example.test"),
                Arguments.of("insomnia", tempFile("insomnia.json", """
                        {
                          "resources": [
                            {
                              "_type": "environment",
                              "name": "UAT",
                              "data": {
                                "base_url": "https://insomnia.example.test",
                                "token": "insomnia-token"
                              }
                            }
                          ]
                        }
                        """), "UAT", "base_url", "https://insomnia.example.test"),
                Arguments.of("bruno", tempFile("bruno.bru", """
                        meta {
                          name: UAT
                        }
                        vars {
                          base_url: https://bruno.example.test
                          token: bruno-token
                        }
                        """), "bruno", "base_url", "https://bruno.example.test")
        );
    }

    private static ImporterPanel newPanel() {
        UniversalImporter importer = Mockito.mock(UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any(EditorOptions.class))).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any(EditorOptions.class))).thenReturn(responseEditor);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        CollectionRunner runner = new CollectionRunner(null);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static Path tempFile(String fileName, String content) throws IOException {
        Path dir = Files.createTempDirectory("environment-drop-");
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return file;
    }

    private static JTextArea importLog(ImporterPanel panel) throws Exception {
        return (JTextArea) privateField(panel, "importLog");
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static EnvironmentProfile environment(String name, String baseUrl) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.ensureId();
        profile.ensureDefaults();
        profile.variables.put("base_url", baseUrl);
        return profile;
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
