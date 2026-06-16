package burp.history;

public class HistoryHeader {
    public String name;
    public String value;
    public boolean disabled;

    public HistoryHeader() {
    }

    public HistoryHeader(String name, String value) {
        this(name, value, false);
    }

    public HistoryHeader(String name, String value, boolean disabled) {
        this.name = name;
        this.value = value;
        this.disabled = disabled;
    }

    public static HistoryHeader copyOf(HistoryHeader source) {
        if (source == null) {
            return null;
        }
        return new HistoryHeader(source.name, source.value, source.disabled);
    }
}
