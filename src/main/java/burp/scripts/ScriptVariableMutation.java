package burp.scripts;

public class ScriptVariableMutation {
    public String key;
    public String oldValue;
    public String newValue;
    public String scope;
    public boolean persistent;
    public String sourceScriptId;
    public String sourceScriptName;

    public ScriptVariableMutation() {
    }

    public ScriptVariableMutation(String key, String oldValue, String newValue, String scope, boolean persistent) {
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.scope = scope;
        this.persistent = persistent;
    }
}
