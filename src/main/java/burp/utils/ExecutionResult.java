package burp.utils;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Result of a single request execution through the shared pipeline.
 */
public class ExecutionResult {
    public boolean success;
    public HttpRequestResponse response;
    public HttpRequest builtRequest;
    public final Map<String, String> extractedVars = new HashMap<>();
    public long elapsedMs;
    public String errorMessage;
    public String requestHeaders;
    public String requestBody;
}
