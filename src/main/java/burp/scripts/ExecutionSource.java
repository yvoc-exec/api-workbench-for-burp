package burp.scripts;

public enum ExecutionSource {
    WORKBENCH_SEND,
    RUNNER,
    BUILD_PREVIEW;

    public boolean isRunner() {
        return this == RUNNER;
    }

    public boolean isWorkbenchSend() {
        return this == WORKBENCH_SEND;
    }
}
