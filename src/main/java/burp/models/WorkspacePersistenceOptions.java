package burp.models;

public class WorkspacePersistenceOptions {
    public boolean persistCollections = true;
    public boolean persistRuntimeVars = true;
    public boolean persistOAuthRuntime = true;
    public boolean persistSensitiveRuntimeValues = false;
    public boolean persistOAuthTokens = false;

    public static WorkspacePersistenceOptions defaults() {
        return new WorkspacePersistenceOptions();
    }
}
