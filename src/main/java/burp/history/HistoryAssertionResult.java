package burp.history;

public class HistoryAssertionResult {
    public String name;
    public boolean passed;
    public String expected;
    public String actual;
    public String message;

    public HistoryAssertionResult() {
    }

    public HistoryAssertionResult(String name, boolean passed, String expected, String actual) {
        this(name, passed, expected, actual, null);
    }

    public HistoryAssertionResult(String name, boolean passed, String expected, String actual, String message) {
        this.name = name;
        this.passed = passed;
        this.expected = expected;
        this.actual = actual;
        this.message = message;
    }

    public static HistoryAssertionResult copyOf(HistoryAssertionResult source) {
        if (source == null) {
            return null;
        }
        return new HistoryAssertionResult(source.name, source.passed, source.expected, source.actual, source.message);
    }
}
