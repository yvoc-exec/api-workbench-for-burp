package burp.history;

public enum HistorySource {
    WORKBENCH("Workbench"),
    RUNNER("Runner"),
    BURP_TRAFFIC("Burp Traffic");

    private final String displayName;

    HistorySource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
