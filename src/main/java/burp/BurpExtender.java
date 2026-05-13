package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;

/**
 * Universal API Collection Importer & Runner for Burp Suite
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
 * Author: Sachinico De Leon (based on nerdygenii/postman-burp-importer)
 * Version: 2.0.0
 * License: MIT
 */
public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private UniversalImporter importer;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;

        api.extension().setName("Universal API Importer & Runner");

        api.logging().logToOutput("═══════════════════════════════════════════════════");
        api.logging().logToOutput("  Universal API Importer & Runner v2.0.0");
        api.logging().logToOutput("  Supports: Postman, Bruno, OpenAPI, Insomnia, HAR");
        api.logging().logToOutput("  Features: Import + Collection Runner");
        api.logging().logToOutput("═══════════════════════════════════════════════════");
        api.logging().logToOutput("Extension loaded successfully!");

        SwingUtilities.invokeLater(() -> {
            importer = new UniversalImporter(api);
            api.userInterface().registerSuiteTab("API Importer", importer.getMainPanel());
        });
    }
}
