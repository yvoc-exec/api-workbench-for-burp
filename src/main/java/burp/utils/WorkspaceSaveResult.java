package burp.utils;

public record WorkspaceSaveResult(
        long revision,
        Status status,
        long serializedLengthBytes,
        String sha256,
        String failureReason) {

    public enum Status {
        SAVED,
        UNCHANGED,
        SUPERSEDED,
        NO_STORE,
        REJECTED_TOO_LARGE,
        TIMED_OUT,
        FAILED,
        CLOSED
    }

    public boolean successful() {
        return status == Status.SAVED || status == Status.UNCHANGED || status == Status.NO_STORE;
    }

    public boolean persisted() {
        return status == Status.SAVED;
    }
}
