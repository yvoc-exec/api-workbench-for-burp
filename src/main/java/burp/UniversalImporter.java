package burp;

import burp.models.*;
import burp.parser.*;
import burp.ui.ImporterPanel;
import burp.auth.OAuth2Manager;
import burp.utils.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.core.ByteArray;

import javax.swing.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core importer logic. Handles parsing, variable resolution, and sending to Burp tools.
 */
public class UniversalImporter {
    private final MontoyaApi api;
    private final VariableResolver resolver;
    private final RequestBuilder requestBuilder;
    private final Set<String> existingTabs = ConcurrentHashMap.newKeySet();
    private final ImporterPanel ui;

    public UniversalImporter(MontoyaApi api) {
        this.api = api;
        this.resolver = new VariableResolver();
        OAuth2Manager oauth2Manager = new OAuth2Manager(api);
        this.requestBuilder = new RequestBuilder(api, resolver, oauth2Manager);
        this.ui = new ImporterPanel(this, new burp.runner.CollectionRunner(api, oauth2Manager), oauth2Manager);
    }

    public JPanel getMainPanel() {
        return ui.getPanel();
    }

    public ImporterPanel getUI() {
        return ui;
    }

    public void importRequests(ApiCollection collection, List<ApiRequest> selectedRequests,
                                File environmentFile, List<String> destinations, int delayMs,
                                LogCallback logCallback, ResultCallback resultCallback) {

        SwingWorker<ImportResult, String> worker = new SwingWorker<>() {
            @Override
            protected ImportResult doInBackground() throws Exception {
                ImportResult result = new ImportResult();
                result.collectionName = collection.name;
                result.totalRequests = selectedRequests.size();

                try {
                    // Load environment if provided
                    if (environmentFile != null) {
                        publish("Loading environment...");
                        try (FileReader reader = new FileReader(environmentFile)) {
                            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                            if (obj.has("values") && obj.get("values").isJsonArray()) {
                                for (com.google.gson.JsonElement v : obj.getAsJsonArray("values")) {
                                    com.google.gson.JsonObject var = v.getAsJsonObject();
                                    if (var.has("key") && var.has("value")) {
                                        resolver.addCustomVariable(var.get("key").getAsString(), var.get("value").getAsString());
                                    }
                                }
                            }
                        }
                    }

                    // Add collection variables
                    resolver.addCollectionVariables(collection);

                    publish("Processing " + selectedRequests.size() + " requests...");

                    for (int i = 0; i < selectedRequests.size(); i++) {
                        if (isCancelled()) break;

                        ApiRequest req = selectedRequests.get(i);
                        try {
                            processRequest(req, destination, delayMs);
                            result.successCount++;
                            publish("✓ " + req.name);
                        } catch (Exception e) {
                            result.failedRequestDetails.add(new ImportResult.FailedRequestInfo(
                                req.name, req.path, e.getMessage(), req));
                            result.failedRequests.add(req.name + ": " + e.getMessage());
                            publish("✗ " + req.name + " - " + e.getMessage());
                        }
                        setProgress((i + 1) * 100 / selectedRequests.size());
                    }
                } catch (Exception e) {
                    result.error = e.getMessage();
                    publish("Fatal error: " + e.getMessage());
                }
                return result;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) logCallback.log(msg);
            }

            @Override
            protected void done() {
                try {
                    ImportResult result = get();
                    resultCallback.onResult(result);
                } catch (Exception e) {
                    logCallback.log("Import failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void processRequest(ApiRequest req, String destination, int delayMs) throws Exception {
        // Add request-level variables
        resolver.addRequestVariables(req);

        byte[] rawRequest = requestBuilder.buildRequest(req);
        String resolvedUrl = resolver.resolve(req.url);
        HttpUtils.HostInfo hostInfo = HttpUtils.parseUrl(resolvedUrl);

        burp.api.montoya.http.HttpService service = burp.api.montoya.http.HttpService.httpService(
            hostInfo.host, hostInfo.port, hostInfo.useHttps);

        HttpRequest httpRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(rawRequest));
        String tabName = generateUniqueTabName(req.name, req.sourceCollection != null ? req.sourceCollection : "Unknown");

        switch (destination.toLowerCase()) {
            case "repeater":
                api.repeater().sendToRepeater(httpRequest, tabName);
                break;
            case "intruder":
                api.intruder().sendToIntruder(httpRequest);
                break;
            case "sitemap":
                if (delayMs > 0) Thread.sleep(delayMs);
                sendToSitemap(service, rawRequest, req.name);
                break;

        }
        existingTabs.add(tabName);
    }

    private void sendToSitemap(burp.api.montoya.http.HttpService service, byte[] request, String name) throws Exception {
        try {
            HttpRequest httpRequest = HttpRequest.httpRequest(service, ByteArray.byteArray(request));
            burp.api.montoya.http.message.HttpRequestResponse response = api.http().sendRequest(httpRequest);
            if (response != null && response.response() != null) {
                api.siteMap().add(response);
            }
        } catch (Exception e) {
            throw new Exception("Sitemap request failed: " + extractCleanError(e));
        }
    }

    private String generateUniqueTabName(String baseName, String collectionName) {
        String tabName = baseName;
        // If duplicate exists, prefix with collection name
        if (existingTabs.contains(tabName)) {
            tabName = collectionName + " - " + baseName;
        }
        // If still duplicate, add counter
        int counter = 1;
        while (existingTabs.contains(tabName)) {
            tabName = collectionName + " - " + baseName + " (" + counter++ + ")";
        }
        return tabName;
    }

    private String extractCleanError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.contains("UnknownHostException")) return "DNS failed - check network/VPN";
        if (msg.contains("ConnectException")) return "Connection refused";
        if (msg.contains("SocketTimeoutException")) return "Connection timeout";
        return msg;
    }

    public void clearVariables() {
        resolver.clear();
        existingTabs.clear();
    }

    public void cleanup() {
        clearVariables();
    }

    // Callback interfaces
    public interface LogCallback {
        void log(String message);
    }

    public interface ResultCallback {
        void onResult(ImportResult result);
    }
}
