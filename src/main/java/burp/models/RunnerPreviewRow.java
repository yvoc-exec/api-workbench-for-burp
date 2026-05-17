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
}
