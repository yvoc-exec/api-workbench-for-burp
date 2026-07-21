package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ExactHttpRequestSnapshotMigrationSupportTest {
    @Test
    void migratesOnlyMatchingLegacyFingerprint() {
        ApiRequest request = legacyRequest("GET / HTTP/1.0\r\nHost: example.test\r\n\r\n");

        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isTrue();
        assertThat(request.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.0");
        assertThat(request.exactHttpRequest.semanticFingerprint)
                .isEqualTo(request.computeSemanticFingerprint());
    }

    @Test
    void doesNotMigrateGenuinelyChangedRequest() {
        ApiRequest request = legacyRequest("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n");
        String stored = request.exactHttpRequest.semanticFingerprint;
        request.url = "https://example.test/changed";

        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isFalse();
        assertThat(request.exactHttpRequest.semanticFingerprint).isEqualTo(stored);
        assertThat(request.exactHttpRequest.httpVersion).isNull();
    }

    @Test
    void infersOnlyTrustedHttp10OrHttp11() {
        assertMigratedVersion("GET / HTTP/1.0\r\nHost: example.test\r\n\r\n", "HTTP/1.0");
        assertMigratedVersion("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1");
        assertMigratedVersion("GET / HTTP/2\r\nHost: example.test\r\n\r\n", null);
        assertMigratedVersion("malformed request line\r\n\r\n", null);
    }

    @Test
    void migrationIsIdempotent() {
        ApiRequest request = legacyRequest("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n");
        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isTrue();
        String fingerprint = request.exactHttpRequest.semanticFingerprint;

        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isFalse();
        assertThat(request.exactHttpRequest.semanticFingerprint).isEqualTo(fingerprint);
        assertThat(request.exactHttpRequest.httpVersion).isEqualTo("HTTP/1.1");
    }

    @Test
    void preservesSnapshotStateAndRawBytes() {
        ApiRequest request = legacyRequest("POST / HTTP/1.0\r\nContent-Length: 1\r\n\r\nx");
        ExactHttpRequestSnapshot snapshot = request.exactHttpRequest;
        byte[] original = snapshot.rawRequestBytes.clone();
        snapshot.pristine = true;
        snapshot.binaryBody = true;
        snapshot.sourceContext = "legacy-source";
        snapshot.invalidationReason = "existing-reason";
        snapshot.serviceHost = "service.example";
        snapshot.servicePort = 8443;
        snapshot.secure = true;

        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isTrue();
        assertThat(snapshot.rawRequestBytes).isEqualTo(original);
        assertThat(snapshot.pristine).isTrue();
        assertThat(snapshot.binaryBody).isTrue();
        assertThat(snapshot.sourceContext).isEqualTo("legacy-source");
        assertThat(snapshot.invalidationReason).isEqualTo("existing-reason");
        assertThat(snapshot.serviceHost).isEqualTo("service.example");
        assertThat(snapshot.servicePort).isEqualTo(8443);
        assertThat(snapshot.secure).isTrue();
    }

    private static void assertMigratedVersion(String raw, String expected) {
        ApiRequest request = legacyRequest(raw);
        assertThat(ExactHttpRequestSnapshotMigrationSupport
                .migrateLegacySemanticFingerprint(request)).isTrue();
        assertThat(request.exactHttpRequest.httpVersion).isEqualTo(expected);
        assertThat(request.exactHttpRequest.semanticFingerprint)
                .isEqualTo(request.computeSemanticFingerprint());
    }

    private static ApiRequest legacyRequest(String raw) {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = "https://example.test/";
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = raw.getBytes(StandardCharsets.UTF_8);
        request.exactHttpRequest.pristine = true;
        request.exactHttpRequest.semanticFingerprint = request.computeLegacySemanticFingerprintV1();
        return request;
    }
}
