package burp.importer;

import burp.history.HistoryHeader;
import burp.history.HistoryResponseSnapshot;
import burp.payload.ManagedPayloadRef;
import burp.payload.ManagedPayloadStage;
import burp.payload.ManagedPayloadStore;

import java.io.IOException;
import java.util.List;

/** Staged traffic metadata. New production captures do not retain complete message arrays. */
public final class BurpTrafficSelection implements AutoCloseable {
    /** Legacy test/migration input only. */
    public final byte[] rawRequestBytes;
    /** Legacy test/migration input only. */
    public final byte[] rawResponseBytes;
    public final ManagedPayloadRef exactPayload;
    public final long bodyOffset;
    public final long bodyLength;
    public final String bodySha256;
    public final boolean binaryBody;
    public final String serviceHost;
    public final int servicePort;
    public final boolean secure;
    public final String sourceContext;
    public final String suggestedDisplayName;
    public final String fallbackMethod;
    public final String target;
    public final String httpVersion;
    public final List<HistoryHeader> headers;
    public final String parseWarning;
    public final HistoryResponseSnapshot responseSnapshot;
    public final int encounterIndex;
    public final long declaredRequestLength;
    public final long declaredResponseLength;
    private final ManagedPayloadStage stage;

    public BurpTrafficSelection(byte[] request, byte[] response, String host, int port,
                                boolean secure, String context, String name, String method,
                                int encounterIndex) {
        this(request, response, null, null, 0L, request != null ? request.length : 0L, "", false,
                host, port, secure, context, name, method, "", "HTTP/1.1", List.of(),
                "", null, encounterIndex, request != null ? request.length : 0L,
                response != null ? response.length : 0L, false);
    }

    private BurpTrafficSelection(byte[] request, byte[] response, ManagedPayloadRef exactPayload,
                                 ManagedPayloadStage stage, long bodyOffset, long bodyLength,
                                 String bodySha256, boolean binaryBody,
                                 String host, int port, boolean secure, String context, String name,
                                 String method, String target, String httpVersion,
                                 List<HistoryHeader> headers, String parseWarning,
                                 HistoryResponseSnapshot responseSnapshot, int encounterIndex,
                                 long requestLength, long responseLength, boolean takeOwnership) {
        this.rawRequestBytes = request != null ? (takeOwnership ? request : request.clone()) : null;
        this.rawResponseBytes = response != null ? (takeOwnership ? response : response.clone()) : null;
        this.exactPayload = exactPayload != null ? exactPayload.copy() : null;
        this.stage = stage;
        this.bodyOffset = Math.max(0L, bodyOffset);
        this.bodyLength = Math.max(0L, bodyLength);
        this.bodySha256 = bodySha256 != null ? bodySha256 : "";
        this.binaryBody = binaryBody;
        this.serviceHost = host;
        this.servicePort = port;
        this.secure = secure;
        this.sourceContext = context;
        this.suggestedDisplayName = name;
        this.fallbackMethod = method;
        this.target = target;
        this.httpVersion = httpVersion;
        this.headers = headers != null ? List.copyOf(headers) : List.of();
        this.parseWarning = parseWarning != null ? parseWarning : "";
        this.responseSnapshot = HistoryResponseSnapshot.copyOf(responseSnapshot);
        this.encounterIndex = encounterIndex;
        this.declaredRequestLength = Math.max(0L, requestLength);
        this.declaredResponseLength = Math.max(0L, responseLength);
    }

    public static BurpTrafficSelection staged(ManagedPayloadRef ref, ManagedPayloadStage stage,
                                               long bodyOffset, long bodyLength,
                                               String bodySha256, boolean binaryBody,
                                               String host, int port, boolean secure,
                                               String context, String name, String method,
                                               String target, String httpVersion,
                                               List<HistoryHeader> headers, String parseWarning,
                                               HistoryResponseSnapshot response, int encounterIndex,
                                               long responseLength) {
        return new BurpTrafficSelection(null, null, ref, stage, bodyOffset, bodyLength,
                bodySha256, binaryBody,
                host, port, secure, context, name, method, target, httpVersion, headers,
                parseWarning, response, encounterIndex, ref != null ? ref.length : 0L,
                responseLength, true);
    }

    public static BurpTrafficSelection fromOwnedBytes(byte[] request, byte[] response,
                                                       String host, int port, boolean secure,
                                                       String context, String name, String method,
                                                       int encounterIndex) {
        return new BurpTrafficSelection(request, response, null, null, 0L,
                request != null ? request.length : 0L, "", false, host, port, secure, context, name,
                method, "", "HTTP/1.1", List.of(), "", null, encounterIndex,
                request != null ? request.length : 0L, response != null ? response.length : 0L, true);
    }

    public boolean isManaged() {
        return exactPayload != null;
    }

    public void commit(ManagedPayloadStore store) throws IOException {
        if (stage == null) return;
        ManagedPayloadRef committed = store.commit(stage);
        if (!committed.equals(exactPayload)) {
            throw new IOException("Managed payload changed during promotion.");
        }
    }

    @Override
    public void close() throws IOException {
        if (stage != null) stage.close();
    }
}
