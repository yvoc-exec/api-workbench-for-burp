package burp.utils;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.RunnerResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of a single request execution through the shared pipeline.
 */
public class ExecutionResult {
    public boolean success;
    public HttpRequestResponse response;
    public HttpRequest builtRequest;
    public final Map<String, String> extractedVars = new HashMap<>();
    public final Set<String> removedVars = new LinkedHashSet<>();
    public final List<RunnerResult.AssertionResult> assertions = new ArrayList<>();
    public long elapsedMs;
    public String errorMessage;
    public String requestHeaders;
    public String requestBody;
    public String resolvedUrl;
}
