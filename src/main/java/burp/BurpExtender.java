package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.parser.ParserRegistry;
import burp.scripts.capabilities.ScriptTrustReviewModel;
import burp.ui.ScriptTrustReviewDialog;
import burp.ui.contextmenu.ApiWorkbenchContextMenuProvider;
import burp.ui.history.HistoryEvidenceBundleUiInstaller;
import burp.ui.traffic.BurpTrafficWorkflowCoordinator;
import burp.utils.ScriptModeDetector;
import burp.utils.WorkspaceStateService;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API Workbench for Burp Suite.
 */
public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private volatile UniversalImporter importer;
    private volatile BurpTrafficWorkflowCoordinator trafficWorkflowCoordinator;
    private volatile ApiWorkbenchContextMenuProvider contextMenuProvider;

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
            ParserRegistry.clearScriptTrustReviewHandler();
            closeContextMenuProvider();
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

            installScriptTrustReviewHandler(mainPanel);
            api.logging().logToOutput("Registering API Workbench suite tab...");
            api.userInterface().registerSuiteTab("API Workbench", mainPanel);

            api.logging().logToOutput("Restoring API Workbench workspace state...");
            importer.restoreWorkspaceStateAfterUiRegistration();

            new HistoryEvidenceBundleUiInstaller().install(mainPanel);
            registerContextMenuProvider(api);
            api.logging().logToOutput("API Workbench suite tab registered successfully.");
        } catch (Throwable t) {
            ParserRegistry.clearScriptTrustReviewHandler();
            closeContextMenuProvider();
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

    private void installScriptTrustReviewHandler(JPanel mainPanel) {
        ParserRegistry.setScriptTrustReviewHandler(model -> {
            if (model == null || model.totalScriptCount() == 0) {
                return ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED;
            }
            AtomicReference<ScriptTrustReviewModel.Decision> decision = new AtomicReference<>(
                    ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED);
            Runnable review = () -> {
                Window owner = SwingUtilities.getWindowAncestor(mainPanel);
                decision.set(new ScriptTrustReviewDialog(owner, model).showDialog());
            };
            if (SwingUtilities.isEventDispatchThread()) {
                review.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(review);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ScriptTrustReviewModel.Decision.CANCEL_IMPORT;
                } catch (InvocationTargetException failure) {
                    return ScriptTrustReviewModel.Decision.CANCEL_IMPORT;
                }
            }
            return decision.get();
        });
    }

    synchronized boolean registerContextMenuProvider(MontoyaApi api) {
        if (contextMenuProvider != null && contextMenuProvider.isRegistered()) {
            return true;
        }
        if (importer == null || api == null) {
            return false;
        }
        trafficWorkflowCoordinator = new BurpTrafficWorkflowCoordinator(importer);
        ApiWorkbenchContextMenuProvider provider = new ApiWorkbenchContextMenuProvider(
                trafficWorkflowCoordinator::importTraffic);
        if (!provider.register(api)) {
            provider.close();
            trafficWorkflowCoordinator = null;
            return false;
        }
        contextMenuProvider = provider;
        api.logging().logToOutput("API Workbench Burp traffic context menu registered.");
        return true;
    }

    synchronized void closeContextMenuProvider() {
        ApiWorkbenchContextMenuProvider provider = contextMenuProvider;
        contextMenuProvider = null;
        trafficWorkflowCoordinator = null;
        if (provider != null) {
            provider.close();
        }
    }

    ApiWorkbenchContextMenuProvider contextMenuProviderForTests() {
        return contextMenuProvider;
    }
}
