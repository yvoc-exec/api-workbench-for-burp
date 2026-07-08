package burp.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable import execution summary used by legacy import and send-to-Burp flows.
 *
 * <p>The fields remain public because older UI, runner, and test code writes to
 * the result object directly while a SwingWorker is building a summary. Helper
 * methods are provided for newer code that wants one place to record failures.</p>
 */
public class ImportResult {
    public String collectionName;
    public String error;
    public int totalRequests;
    public int successCount;
    public List<FailedRequestInfo> failedRequestDetails;
    public List<String> failedRequests;

    public ImportResult() {
        this.failedRequestDetails = new ArrayList<>();
        this.failedRequests = new ArrayList<>();
    }

    public ImportResult(String collectionName, int totalRequests) {
        this();
        this.collectionName = collectionName;
        this.totalRequests = totalRequests;
    }

    public void recordSuccess() {
        successCount++;
    }

    public void recordFailure(String name, String path, String message, Object sourceRequest) {
        String safeName = name != null ? name : "";
        String safeMessage = message != null ? message : "";
        failedRequestDetails.add(new FailedRequestInfo(safeName, path, safeMessage, sourceRequest));
        failedRequests.add(safeName + ": " + safeMessage);
    }

    public int failureCount() {
        return failedRequestDetails != null ? failedRequestDetails.size() : 0;
    }

    public boolean hasFailures() {
        return failureCount() > 0;
    }

    public boolean hasFatalError() {
        return error != null && !error.isBlank();
    }

    public static class FailedRequestInfo {
        public String errorMessage;
        public Object requestData;
        public String name;
        public String path;

        public FailedRequestInfo(String name, String path, String errorMessage, Object requestData) {
            this.errorMessage = errorMessage;
            this.requestData = requestData;
            this.name = name;
            this.path = path;
        }
    }
}
