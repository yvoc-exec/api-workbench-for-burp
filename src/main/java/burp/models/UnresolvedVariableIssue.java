package burp.models;

public class UnresolvedVariableIssue {
    public String collectionName;
    public String requestName;
    public String variableName;
    public String location;
    public String message;

    public UnresolvedVariableIssue(String collectionName, String requestName, String variableName, String location) {
        this(collectionName, requestName, variableName, location, null);
    }

    public UnresolvedVariableIssue(String collectionName, String requestName, String variableName, String location, String message) {
        this.collectionName = collectionName;
        this.requestName = requestName;
        this.variableName = variableName;
        this.location = location;
        this.message = message;
    }
}
