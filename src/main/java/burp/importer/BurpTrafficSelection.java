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
    public final long declaredRequestLength;
    public final long declaredResponseLength;

    public BurpTrafficSelection(byte[] rawRequestBytes,
                                byte[] rawResponseBytes,
                                String serviceHost,
                                int servicePort,
                                boolean secure,
                                String sourceContext,
                                String suggestedDisplayName,
                                String fallbackMethod,
                                int encounterIndex) {
        this(rawRequestBytes, rawResponseBytes, serviceHost, servicePort, secure, sourceContext,
                suggestedDisplayName, fallbackMethod, encounterIndex,
                rawRequestBytes != null ? rawRequestBytes.length : 0L,
                rawResponseBytes != null ? rawResponseBytes.length : 0L,
                false);
    }

    private BurpTrafficSelection(byte[] rawRequestBytes,
                                 byte[] rawResponseBytes,
                                 String serviceHost,
                                 int servicePort,
                                 boolean secure,
                                 String sourceContext,
                                 String suggestedDisplayName,
                                 String fallbackMethod,
                                 int encounterIndex,
                                 long declaredRequestLength,
                                 long declaredResponseLength,
                                 boolean takeOwnership) {
        // Public construction detaches mutable caller bytes. The context-menu adapter
        // uses the owned factory after Montoya has already produced detached arrays.
        this.rawRequestBytes = rawRequestBytes != null
                ? (takeOwnership ? rawRequestBytes : rawRequestBytes.clone())
                : new byte[0];
        this.rawResponseBytes = rawResponseBytes != null
                ? (takeOwnership ? rawResponseBytes : rawResponseBytes.clone())
                : new byte[0];
        this.serviceHost = serviceHost;
        this.servicePort = servicePort;
        this.secure = secure;
        this.sourceContext = sourceContext;
        this.suggestedDisplayName = suggestedDisplayName;
        this.fallbackMethod = fallbackMethod;
        this.encounterIndex = encounterIndex;
        this.declaredRequestLength = Math.max(this.rawRequestBytes.length, Math.max(0L, declaredRequestLength));
        this.declaredResponseLength = Math.max(this.rawResponseBytes.length, Math.max(0L, declaredResponseLength));
    }

    public static BurpTrafficSelection fromOwnedBytes(byte[] rawRequestBytes,
                                                      byte[] rawResponseBytes,
                                                      String serviceHost,
                                                      int servicePort,
                                                      boolean secure,
                                                      String sourceContext,
                                                      String suggestedDisplayName,
                                                      String fallbackMethod,
                                                      int encounterIndex) {
        return new BurpTrafficSelection(rawRequestBytes, rawResponseBytes, serviceHost, servicePort,
                secure, sourceContext, suggestedDisplayName, fallbackMethod, encounterIndex,
                rawRequestBytes != null ? rawRequestBytes.length : 0L,
                rawResponseBytes != null ? rawResponseBytes.length : 0L, true);
    }

    public static BurpTrafficSelection rejectedMetadata(long requestLength,
                                                        long responseLength,
                                                        String sourceContext,
                                                        int encounterIndex) {
        return new BurpTrafficSelection(null, null, null, 0, false, sourceContext,
                null, null, encounterIndex, requestLength, responseLength, true);
    }

    public BurpTrafficSelection copy() {
        return new BurpTrafficSelection(
                rawRequestBytes,
                rawResponseBytes,
                serviceHost,
                servicePort,
                secure,
                sourceContext,
                suggestedDisplayName,
                fallbackMethod,
                encounterIndex,
                declaredRequestLength,
                declaredResponseLength,
                false);
    }
}
