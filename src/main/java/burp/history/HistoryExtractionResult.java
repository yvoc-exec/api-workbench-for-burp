package burp.history;

public class HistoryExtractionResult {
    public String name;
    public String value;
    public String source;
    public String message;

    public HistoryExtractionResult() {
    }

    public HistoryExtractionResult(String name, String value) {
        this(name, value, null, null);
    }

    public HistoryExtractionResult(String name, String value, String source, String message) {
        this.name = name;
        this.value = value;
        this.source = source;
        this.message = message;
    }

    public static HistoryExtractionResult copyOf(HistoryExtractionResult source) {
        if (source == null) {
            return null;
        }
        return new HistoryExtractionResult(source.name, source.value, source.source, source.message);
    }
}
