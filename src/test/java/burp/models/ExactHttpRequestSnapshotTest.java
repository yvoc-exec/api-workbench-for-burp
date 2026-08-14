package burp.models;

import burp.history.HistoryBodyTruncator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ExactHttpRequestSnapshotTest {
    @Test
    void binaryPlaceholderHashesCrLfBodyWithoutExtractingIt() {
        byte[] body = new byte[]{0, 1, (byte) 0xff, 42};
        byte[] raw = concat("POST / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8), body);

        assertThat(ExactHttpRequestSnapshot.binaryBodyPlaceholder(raw))
                .isEqualTo("[Binary exact body preserved: 4 bytes; SHA-256="
                        + HistoryBodyTruncator.sha256Hex(body) + "]");
    }

    @Test
    void binaryPlaceholderSupportsLfOnlyAndNoBody() {
        byte[] body = new byte[1024 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] raw = concat("POST / HTTP/1.1\nHost: example.test\n\n".getBytes(StandardCharsets.UTF_8), body);

        assertThat(ExactHttpRequestSnapshot.binaryBodyPlaceholder(raw))
                .contains("1048576 bytes", HistoryBodyTruncator.sha256Hex(body));
        assertThat(ExactHttpRequestSnapshot.binaryBodyPlaceholder(
                "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("[Binary exact body preserved: 0 bytes; SHA-256=]");
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
