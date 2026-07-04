package burp.importer;

public final class BurpTrafficSelection {
    public final byte[] rawRequestBytes;
    public final byte[] rawResponseBytes;
    public final String serviceHost;
    public final int servicePort;
    public final boolean secure;
    public final String sourceContext;
    public final String suggestedDisplayName;
    public final String fallbackMethod;
    public final int encounterIndex;

    public BurpTrafficSelection(byte[] rawRequestBytes,
                                byte[] rawResponseBytes,
                                String serviceHost,
                                int servicePort,
                                boolean secure,
                                String sourceContext,
                                String suggestedDisplayName,
                                String fallbackMethod,
                                int encounterIndex) {
        this.rawRequestBytes = rawRequestBytes != null ? rawRequestBytes.clone() : new byte[0];
        this.rawResponseBytes = rawResponseBytes != null ? rawResponseBytes.clone() : new byte[0];
        this.serviceHost = serviceHost;
        this.servicePort = servicePort;
        this.secure = secure;
        this.sourceContext = sourceContext;
        this.suggestedDisplayName = suggestedDisplayName;
        this.fallbackMethod = fallbackMethod;
        this.encounterIndex = encounterIndex;
    }
}
