package burp.scripts.capabilities;

public enum ScriptRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static ScriptRiskLevel max(ScriptRiskLevel left, ScriptRiskLevel right) {
        ScriptRiskLevel a = left != null ? left : LOW;
        ScriptRiskLevel b = right != null ? right : LOW;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
