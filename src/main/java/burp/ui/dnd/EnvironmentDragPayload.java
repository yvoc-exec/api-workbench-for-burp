package burp.ui.dnd;

/**
 * JVM-local payload for dragging an existing environment profile.
 */
public final class EnvironmentDragPayload {
    public final String environmentId;
    public final String environmentName;

    public EnvironmentDragPayload(String environmentId, String environmentName) {
        this.environmentId = environmentId;
        this.environmentName = environmentName;
    }

    @Override
    public String toString() {
        return "EnvironmentDragPayload{"
                + "environmentId='" + environmentId + '\''
                + ", environmentName='" + environmentName + '\''
                + '}';
    }
}
