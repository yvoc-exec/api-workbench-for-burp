package burp.scripts.capabilities;

import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;

public final class ScriptTrustReviewItem {
    public String blockId = "";
    public String collectionId = "";
    public String collectionName = "";
    public String requestId = "";
    public String requestName = "";
    public String folderPath = "";
    public ScriptDialect dialect;
    public ScriptPhase phase;
    public ScriptScope scope;
    public String sourceFormat = "";
    public String sourcePath = "";
    public String sourcePreview = "";
    public boolean selectedForTrust;
    public ScriptCapabilityReport capabilityReport = new ScriptCapabilityReport();

    public ScriptTrustReviewItem copy() {
        ScriptTrustReviewItem copy = new ScriptTrustReviewItem();
        copy.blockId = blockId;
        copy.collectionId = collectionId;
        copy.collectionName = collectionName;
        copy.requestId = requestId;
        copy.requestName = requestName;
        copy.folderPath = folderPath;
        copy.dialect = dialect;
        copy.phase = phase;
        copy.scope = scope;
        copy.sourceFormat = sourceFormat;
        copy.sourcePath = sourcePath;
        copy.sourcePreview = sourcePreview;
        copy.selectedForTrust = selectedForTrust;
        copy.capabilityReport = capabilityReport;
        return copy;
    }
}
