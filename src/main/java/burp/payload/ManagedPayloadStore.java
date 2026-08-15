package burp.payload;

import java.io.IOException;
import java.util.Set;

public interface ManagedPayloadStore extends ManagedPayloadReader, AutoCloseable {
    ManagedPayloadStage beginStage(String operation) throws IOException;

    ManagedPayloadRef commit(ManagedPayloadStage stage) throws IOException;

    void cleanupStaging() throws IOException;

    void sweepUnreferenced(Set<String> livePayloadIds, Set<String> leasedPayloadIds) throws IOException;

    void updateManifest(long revision, Set<String> livePayloadIds) throws IOException;

    Set<String> loadManifestPayloadIds() throws IOException;

    Set<String> activeLeasePayloadIds();

    long physicalBlobBytes() throws IOException;

    int uniqueBlobCount() throws IOException;

    @Override
    void close() throws IOException;
}
