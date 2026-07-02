package burp.utils;

public final class ExecutionPolicy {
    public enum ScriptFailureMode { ABORT, CONTINUE }
    public enum OAuth2FailureMode { ABORT, USE_STALE_TOKEN, SEND_WITHOUT_TOKEN }
    public enum TargetChangeMode { ALLOW, ABORT, REQUIRE_CONFIRMATION }
    public enum UnresolvedVariableMode { ALLOW_WITH_WARNING, ABORT, REQUIRE_CONFIRMATION }

    public ScriptFailureMode scriptFailureMode;
    public OAuth2FailureMode oauth2FailureMode;
    public TargetChangeMode targetChangeMode;
    public UnresolvedVariableMode unresolvedVariableMode;
    public int responseTimeoutMillis;

    public static ExecutionPolicy workbenchDefaults() {
        ExecutionPolicy policy = new ExecutionPolicy();
        policy.scriptFailureMode = ScriptFailureMode.ABORT;
        policy.oauth2FailureMode = OAuth2FailureMode.ABORT;
        policy.targetChangeMode = TargetChangeMode.REQUIRE_CONFIRMATION;
        policy.unresolvedVariableMode = UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        policy.responseTimeoutMillis = 30_000;
        return policy;
    }

    public static ExecutionPolicy runnerDefaults(boolean stopOnMissingVariable) {
        ExecutionPolicy policy = new ExecutionPolicy();
        policy.scriptFailureMode = ScriptFailureMode.ABORT;
        policy.oauth2FailureMode = OAuth2FailureMode.ABORT;
        policy.targetChangeMode = TargetChangeMode.ABORT;
        policy.unresolvedVariableMode = stopOnMissingVariable ? UnresolvedVariableMode.ABORT : UnresolvedVariableMode.ALLOW_WITH_WARNING;
        policy.responseTimeoutMillis = 30_000;
        return policy;
    }

    public static ExecutionPolicy previewDefaults() {
        ExecutionPolicy policy = new ExecutionPolicy();
        policy.scriptFailureMode = ScriptFailureMode.ABORT;
        policy.oauth2FailureMode = OAuth2FailureMode.ABORT;
        policy.targetChangeMode = TargetChangeMode.ALLOW;
        policy.unresolvedVariableMode = UnresolvedVariableMode.ALLOW_WITH_WARNING;
        policy.responseTimeoutMillis = 30_000;
        return policy;
    }

    public ExecutionPolicy copy() {
        ExecutionPolicy copy = new ExecutionPolicy();
        copy.scriptFailureMode = scriptFailureMode;
        copy.oauth2FailureMode = oauth2FailureMode;
        copy.targetChangeMode = targetChangeMode;
        copy.unresolvedVariableMode = unresolvedVariableMode;
        copy.responseTimeoutMillis = responseTimeoutMillis;
        return copy;
    }

    public void normalize() {
        if (scriptFailureMode == null) scriptFailureMode = ScriptFailureMode.ABORT;
        if (oauth2FailureMode == null) oauth2FailureMode = OAuth2FailureMode.ABORT;
        if (targetChangeMode == null) targetChangeMode = TargetChangeMode.REQUIRE_CONFIRMATION;
        if (unresolvedVariableMode == null) unresolvedVariableMode = UnresolvedVariableMode.REQUIRE_CONFIRMATION;
        if (responseTimeoutMillis <= 0) responseTimeoutMillis = 30_000;
        if (responseTimeoutMillis < 1_000) responseTimeoutMillis = 1_000;
        if (responseTimeoutMillis > 300_000) responseTimeoutMillis = 300_000;
    }
}
