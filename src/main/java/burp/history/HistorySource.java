package burp.history;

public enum HistorySource {
    WORKBENCH("Workbench"),
    RUNNER("Runner");

    private final String displayName;

    HistorySource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
