package burp.history;

public enum HistoryReplayRedirectMode {
    RECORDED("Use recorded behavior"),
    ALWAYS_FOLLOW("Always follow"),
    NEVER_FOLLOW("Never follow");

    private final String displayLabel;

    HistoryReplayRedirectMode(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public static HistoryReplayRedirectMode fromPersisted(String value) {
        if (value == null || value.isBlank()) {
            return RECORDED;
        }
        for (HistoryReplayRedirectMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return RECORDED;
    }

    @Override
    public String toString() {
        return displayLabel;
    }
}
