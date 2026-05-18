package burp.models;

/**
 * Represents a bearer-token alias discovered inside a collection.
 */
public class BearerTokenAliasCandidate {
    public String alias;
    public int requestCount;
    public String currentValue;
    public boolean defaultSelected;
    public String overwriteStatus;

    public BearerTokenAliasCandidate() {
    }

    public BearerTokenAliasCandidate(String alias,
                                     int requestCount,
                                     String currentValue,
                                     boolean defaultSelected,
                                     String overwriteStatus) {
        this.alias = alias;
        this.requestCount = requestCount;
        this.currentValue = currentValue;
        this.defaultSelected = defaultSelected;
        this.overwriteStatus = overwriteStatus;
    }
}
