package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.utils.ScriptModeDetector;
import burp.utils.WorkspaceStateService;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * API Workbench for Burp Suite
 *
 * Supports: Postman (v2.0/v2.1), Bruno (.bru), OpenAPI/Swagger (JSON/YAML),
 *           Insomnia (v4), HAR
 *
 * Features:
 * - Auto-detect collection format
 * - Preview and select requests before import
 * - Import to Repeater, Sitemap, or Both
 * - Variable resolution with environment files
 * - Collection Runner: sequential execution with variable extraction
 * - Rate limiting and retry logic
 *
 * Author: Sachinico De Leon
 * Version: 2.0.0
 * License: MIT
 */
public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private volatile UniversalImporter importer;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;

        ScriptModeDetector.DetectionResult scriptResult = ScriptModeDetector.detect();
        api.extension().setName("API Workbench for Burp");
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("  API Workbench for Burp v2.0.0");
        api.logging().logToOutput("  Supports: Postman, Bruno, OpenAPI, Insomnia, HAR");
        api.logging().logToOutput("  Features: Import + Collection Runner + Workbench");
        api.logging().logToOutput("  Java: " + scriptResult.javaVersion + " | Script: " + scriptResult.mode.label);
        api.logging().logToOutput("  Script engine: " + (scriptResult.engineName != null ? scriptResult.engineName : "Unavailable"));
        if (scriptResult.reason != null) {
            api.logging().logToOutput("  Script reason: " + scriptResult.reason);
        }
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("Extension core initialized; scheduling API Workbench UI registration...");

        Runnable uiInit = () -> initializeUi(api, scriptResult);
        if (GraphicsEnvironment.isHeadless()) {
            uiInit.run();
        } else {
            SwingUtilities.invokeLater(uiInit);
        }

        api.extension().registerUnloadingHandler(() -> {
            if (importer != null) {
                importer.cleanup();
            }
            burp.auth.TokenStore.clearAll();
            api.logging().logToOutput("API Workbench for Burp unloaded. Tokens cleared.");
        });
    }

    void initializeUi(MontoyaApi api, ScriptModeDetector.DetectionResult scriptResult) {
        try {
            api.logging().logToOutput("API Workbench UI init starting...");

            api.logging().logToOutput("Creating WorkspaceStateService...");
            WorkspaceStateService workspaceStateService = new WorkspaceStateService(api);

            api.logging().logToOutput("Creating UniversalImporter...");
            importer = new UniversalImporter(api, scriptResult.mode, workspaceStateService);

            api.logging().logToOutput("Getting API Workbench main panel...");
            JPanel mainPanel = importer.getMainPanel();
            if (mainPanel == null) {
                throw new IllegalStateException("API Workbench main panel is null.");
            }

            api.logging().logToOutput("Registering API Workbench suite tab...");
            api.userInterface().registerSuiteTab("API Workbench", mainPanel);

            api.logging().logToOutput("Restoring API Workbench workspace state...");
            importer.restoreWorkspaceStateAfterUiRegistration();

            api.logging().logToOutput("API Workbench suite tab registered successfully.");
        } catch (Throwable t) {
            api.logging().logToError("API Workbench UI initialization failed: " + t);
            StringWriter traceWriter = new StringWriter();
            t.printStackTrace(new PrintWriter(traceWriter));
            String[] traceLines = traceWriter.toString().split("\\R");
            for (int i = 1; i < traceLines.length; i++) {
                if (!traceLines[i].isEmpty()) {
                    api.logging().logToError(traceLines[i]);
                }
            }
        }
    }
}
