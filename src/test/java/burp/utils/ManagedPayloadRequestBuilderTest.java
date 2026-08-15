package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.VariableResolver;
import burp.payload.FileManagedPayloadStore;
import burp.payload.ManagedPayloadRef;
import burp.payload.ManagedPayloadStage;
import burp.payload.PayloadSliceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedPayloadRequestBuilderTest {
    @TempDir Path temp;

    @Test
    void exactReplayAndHeaderEditReuseOneBlobWithoutRetainingBodyArray() throws Exception {
        byte[] raw = "POST /original HTTP/1.1\r\nHost: example.test\r\nX-Old: yes\r\n\r\nbody-data"
                .getBytes(StandardCharsets.ISO_8859_1);
        int bodyOffset = new String(raw, StandardCharsets.ISO_8859_1).indexOf("body-data");
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            ManagedPayloadRef ref;
            try (ManagedPayloadStage stage = store.beginStage("test")) {
                stage.write(raw);
                ref = store.commit(stage);
            }
            ApiRequest request = request(ref, bodyOffset, raw.length - bodyOffset);
            RequestBuilder builder = new RequestBuilder(null, store);

            assertThat(builder.buildRequest(request, new VariableResolver())).isEqualTo(raw);
            request.headers = new ArrayList<>();
            request.headers.add(new ApiRequest.Header("X-New", "yes", false));
            request.exactHttpRequest.pristine = false;
            byte[] edited = builder.buildRequest(request, new VariableResolver());

            assertThat(new String(edited, StandardCharsets.ISO_8859_1)).contains("X-New: yes").endsWith("body-data");
            assertThat(request.body.raw).isNull();
            assertThat(request.body.managedPayload.payload.payloadId).isEqualTo(ref.payloadId);
            assertThat(store.uniqueBlobCount()).isEqualTo(1);
        }
    }

    @Test
    void missingManagedPayloadNeverFallsBackToEmptyBody() throws Exception {
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            String sha = "0".repeat(64);
            ApiRequest request = request(new ManagedPayloadRef(sha, 12, sha), 8, 4);
            assertThatThrownBy(() -> new RequestBuilder(null, store)
                    .buildRequest(request, new VariableResolver()))
                    .hasMessageContaining("unavailable");
        }
    }

    private static ApiRequest request(ManagedPayloadRef ref, long offset, long length) {
        ApiRequest request = new ApiRequest();
        request.method = "POST";
        request.url = "https://example.test/edited";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.headers = new ArrayList<>();
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.managedPayload = new PayloadSliceRef(ref, offset, length);
        request.exactHttpRequest = ExactHttpRequestSnapshot.fromManagedPayload(
                ref, "example.test", 443, true, "HTTP/1.1", true, "test", "fingerprint");
        return request;
    }
}
