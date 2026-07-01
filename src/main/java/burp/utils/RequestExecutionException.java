package burp.utils;

import burp.UniversalImporter;

public class RequestExecutionException extends Exception {
    private final ExecutionResult executionResult;
    private final UniversalImporter.SingleSendResult singleSendResult;

    public RequestExecutionException(String message,
                                     ExecutionResult executionResult,
                                     UniversalImporter.SingleSendResult singleSendResult) {
        super(message);
        this.executionResult = executionResult;
        this.singleSendResult = singleSendResult;
    }

    public ExecutionResult getExecutionResult() {
        return executionResult;
    }

    public UniversalImporter.SingleSendResult getSingleSendResult() {
        return singleSendResult;
    }
}
