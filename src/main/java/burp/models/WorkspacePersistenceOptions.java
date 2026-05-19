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

    public static WorkspacePersistenceOptions fullProjectPersistence() {
        WorkspacePersistenceOptions options = defaults();
        options.persistSensitiveRuntimeValues = true;
        options.persistOAuthTokens = true;
        return options;
    }

    public WorkspacePersistenceOptions copy() {
        WorkspacePersistenceOptions copy = new WorkspacePersistenceOptions();
        copy.persistCollections = persistCollections;
        copy.persistRuntimeVars = persistRuntimeVars;
        copy.persistOAuthRuntime = persistOAuthRuntime;
        copy.persistSensitiveRuntimeValues = persistSensitiveRuntimeValues;
        copy.persistOAuthTokens = persistOAuthTokens;
        return copy;
    }
}
