package burp.scripts;

public class ScriptLogEntry {
    public String level = "info";
    public String message;
    public String scriptId;
    public String scriptName;

    public ScriptLogEntry() {
    }

    public ScriptLogEntry(String level, String message, String scriptId, String scriptName) {
        this.level = level;
        this.message = message;
        this.scriptId = scriptId;
        this.scriptName = scriptName;
    }
}
