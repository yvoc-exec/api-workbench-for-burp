package burp.scripts;

public enum ExecutionSource {
    WORKBENCH_SEND,
    HISTORY_REPLAY,
    RUNNER,
    BUILD_PREVIEW;

    public boolean isRunner() {
        return this == RUNNER;
    }

    public boolean isWorkbenchSend() {
        return this == WORKBENCH_SEND;
    }

    public boolean isHistoryReplay() {
        return this == HISTORY_REPLAY;
    }
}
