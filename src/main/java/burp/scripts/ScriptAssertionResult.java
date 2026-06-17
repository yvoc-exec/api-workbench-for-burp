package burp.scripts;

public class ScriptAssertionResult {
    public String name;
    public boolean passed;
    public String expected;
    public String actual;
    public String message;
    public String scriptId;

    public ScriptAssertionResult() {
    }

    public ScriptAssertionResult(String name, boolean passed, String expected, String actual) {
        this.name = name;
        this.passed = passed;
        this.expected = expected;
        this.actual = actual;
    }
}
