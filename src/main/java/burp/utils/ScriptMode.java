package burp.utils;

/**
 * Script execution capability levels.
 */
public enum ScriptMode {
    FULL_JS("Full", "A JavaScript runtime is available. Pre/post scripts execute fully."),
    LIMITED("Limited", "JavaScript runtime probe failed. Only regex-based fallback extraction runs on post-response."),
    DISABLED("Disabled", "Java 17+ is required for script execution.");

    public final String label;
    public final String description;

    ScriptMode(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
