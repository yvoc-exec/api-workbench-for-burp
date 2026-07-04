package burp.scripts;

public final class UnsupportedScriptCapabilityException extends RuntimeException {
    private final ScriptExecutionResult result;

    public UnsupportedScriptCapabilityException(ScriptExecutionResult result) {
        super(message(result));
        this.result = result;
    }

    public ScriptExecutionResult result() {
        return result;
    }

    private static String message(ScriptExecutionResult result) {
        if (result == null || result.unsupportedCapabilities.isEmpty()) {
            return ScriptLifecycleExecutor.UNSUPPORTED_SCRIPT_CAPABILITY;
        }
        StringBuilder names = new StringBuilder();
        for (ScriptUnsupportedCapability issue : result.unsupportedCapabilities) {
            if (issue == null || issue.capabilityName() == null || issue.capabilityName().isBlank()) {
                continue;
            }
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(issue.capabilityName());
        }
        return ScriptLifecycleExecutor.UNSUPPORTED_SCRIPT_CAPABILITY
                + (names.length() > 0 ? ": " + names : "");
    }
}
