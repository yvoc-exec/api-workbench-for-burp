package burp.models;

import java.util.ArrayList;
import java.util.List;

public class RunnerPreviewRow {
    public int order;
    public String collectionName;
    public String requestName;
    public String method;
    public String urlPreview;
    public List<String> unresolvedVariables = new ArrayList<>();
    public String authStatus;
    public boolean retryEligible;
    public int maximumAttempts = 1;
    public String retryPolicySummary;
    public int responseTimeoutMillis;
    public String targetChangePolicy;
}
