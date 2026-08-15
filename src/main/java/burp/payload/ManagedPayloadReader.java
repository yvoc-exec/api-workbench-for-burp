package burp.payload;

import java.io.IOException;
import java.io.InputStream;

public interface ManagedPayloadReader {
    InputStream open(ManagedPayloadRef ref) throws IOException;

    InputStream openSlice(PayloadSliceRef slice) throws IOException;

    ManagedPayloadLease materialize(ManagedPayloadRef ref) throws IOException;

    ManagedPayloadLease materialize(PayloadSliceRef slice) throws IOException;

    boolean exists(ManagedPayloadRef ref);

    void verify(ManagedPayloadRef ref) throws IOException;
}
