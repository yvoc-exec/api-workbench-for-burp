package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;

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
 * Author: yvoc-exec yvoc-exec (based on API Workbench for Burp)
 * Version: 2.0.0
 * License: MIT
 */
public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private UniversalImporter importer;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;

        api.extension().setName("API Workbench for Burp");

        burp.utils.ScriptModeDetector.DetectionResult scriptResult = burp.utils.ScriptModeDetector.detect();
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("  API Workbench for Burp v2.0.0");
        api.logging().logToOutput("  Supports: Postman, Bruno, OpenAPI, Insomnia, HAR");
        api.logging().logToOutput("  Features: Import + Collection Runner + Workbench");
        api.logging().logToOutput("  Java: " + scriptResult.javaVersion + " | Script: " + scriptResult.mode.label);
        if (scriptResult.reason != null) {
            api.logging().logToOutput("  Script reason: " + scriptResult.reason);
        }
        api.logging().logToOutput("  Based on API Workbench for Burp");
        api.logging().logToOutput("===================================================");
        api.logging().logToOutput("Extension loaded successfully!");

        SwingUtilities.invokeLater(() -> {
            importer = new UniversalImporter(api, scriptResult.mode);
            api.userInterface().registerSuiteTab("API Workbench", importer.getMainPanel());
        });

        api.extension().registerUnloadingHandler(() -> {
            if (importer != null) {
                importer.cleanup();
            }
            burp.auth.TokenStore.clearAll();
            api.logging().logToOutput("API Workbench for Burp unloaded. Tokens cleared.");
        });
    }
}
