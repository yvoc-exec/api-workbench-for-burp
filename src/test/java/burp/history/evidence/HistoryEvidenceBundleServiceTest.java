package burp.history.evidence;

import burp.history.HistoryBodyTruncator;
import burp.history.HistoryEntry;
import burp.history.HistoryHeader;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistorySource;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryEvidenceBundleServiceTest {
    private static final Instant EXPORT_TIME = Instant.parse("2026-07-05T01:30:00Z");

    @TempDir
    Path tempDir;

    @Test
    void exportsDeterministicRedactedBundleWithVerifiedHashesAndBinaryBodies() throws Exception {
        HistoryEntry entry = evidenceEntry();
        HistoryEvidenceBundleService service = new HistoryEvidenceBundleService();
        Clock clock = Clock.fixed(EXPORT_TIME, ZoneOffset.UTC);
        Path first = tempDir.resolve("evidence-one.zip");
        Path second = tempDir.resolve("evidence-two.zip");

        HistoryEvidenceBundleService.ExportResult firstResult = service.export(
                List.of(entry),
                new HistoryEvidenceBundleOptions(first, true, clock, "2.0.0-test"));
        service.export(
                List.of(entry),
                new HistoryEvidenceBundleOptions(second, true, clock, "2.0.0-test"));

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        assertThat(firstResult.entryCount()).isEqualTo(1);
        assertThat(firstResult.redactionApplied()).isTrue();
        assertThat(firstResult.sha256()).isEqualTo(HistoryBodyTruncator.sha256Hex(Files.readAllBytes(first)));

        Map<String, byte[]> files = readZipEntries(first);
        assertThat(files.keySet()).containsExactly(
                "manifest.json",
                "summary.csv",
                "entries/entry_1/request.txt",
                "entries/entry_1/response.txt",
                "entries/entry_1/metadata.json",
                "entries/entry_1/notes.txt");

        byte[] request = files.get("entries/entry_1/request.txt");
        String requestHeaders = headerText(request);
        assertThat(requestHeaders)
                .contains("Authorization: [REDACTED]")
                .contains("Cookie: [REDACTED]")
                .contains("access_token=[REDACTED]")
                .doesNotContain("Bearer top-secret")
                .doesNotContain("sid=session-secret")
                .doesNotContain("access_token=query-secret");
        assertThat(bodyBytes(request)).containsExactly((byte) 0x00, (byte) 0xff, (byte) 0x41);

        byte[] response = files.get("entries/entry_1/response.txt");
        assertThat(headerText(response))
                .contains("Set-Cookie: [REDACTED]")
                .doesNotContain("response-secret");
        assertThat(bodyBytes(response)).containsExactly((byte) 0x10, (byte) 0x00, (byte) 0xfe);

        String notes = new String(files.get("entries/entry_1/notes.txt"), StandardCharsets.UTF_8);
        assertThat(notes)
                .contains("Authorization:[REDACTED]")
                .contains("access_token=[REDACTED]")
                .doesNotContain("note-secret");

        JsonObject manifest = JsonParser.parseString(
                new String(files.get("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(manifest.get("format").getAsString()).isEqualTo(HistoryEvidenceBundleService.FORMAT);
        assertThat(manifest.get("version").getAsInt()).isEqualTo(HistoryEvidenceBundleService.VERSION);
        assertThat(manifest.get("exportTimestamp").getAsString()).isEqualTo(EXPORT_TIME.toString());
        assertThat(manifest.get("redactionApplied").getAsBoolean()).isTrue();
        assertThat(manifest.get("entryCount").getAsInt()).isEqualTo(1);

        JsonObject manifestEntry = manifest.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(manifestEntry.get("requestRepresentation").getAsString()).isEqualTo("EXACT_RAW");
        assertThat(manifestEntry.getAsJsonObject("requestTruncation").get("rawBodyTruncated").getAsBoolean()).isTrue();
        assertThat(manifestEntry.getAsJsonObject("responseTruncation").get("bodyTruncated").getAsBoolean()).isTrue();
        JsonObject fileDescriptors = manifestEntry.getAsJsonObject("files");
        assertDescriptor(fileDescriptors.getAsJsonObject("request"), request);
        assertDescriptor(fileDescriptors.getAsJsonObject("response"), response);
        assertDescriptor(fileDescriptors.getAsJsonObject("metadata"), files.get("entries/entry_1/metadata.json"));
        assertDescriptor(fileDescriptors.getAsJsonObject("notes"), files.get("entries/entry_1/notes.txt"));

        String summary = new String(files.get("summary.csv"), StandardCharsets.UTF_8);
        assertThat(summary)
                .contains("api-workbench-evidence")
                .contains("'=@formula")
                .doesNotContain("query-secret");
    }

    @Test
    void unredactedBundlePreservesExactRawRequestBytes() throws Exception {
        HistoryEntry entry = evidenceEntry();
        Path output = tempDir.resolve("raw-evidence.zip");

        new HistoryEvidenceBundleService().export(
                List.of(entry),
                new HistoryEvidenceBundleOptions(
                        output,
                        false,
                        Clock.fixed(EXPORT_TIME, ZoneOffset.UTC),
                        "2.0.0-test"));

        Map<String, byte[]> files = readZipEntries(output);
        assertThat(files.get("entries/entry_1/request.txt"))
                .isEqualTo(entry.requestSnapshot.rawRequestSent);
        assertThat(new String(files.get("entries/entry_1/notes.txt"), StandardCharsets.UTF_8))
                .contains("note-secret");
    }

    private static HistoryEntry evidenceEntry() {
        byte[] requestBody = new byte[]{0x00, (byte) 0xff, 0x41};
        byte[] rawRequest = concat(
                ("POST /upload?access_token=query-secret&ok=1 HTTP/1.1\r\n"
                        + "Host: example.invalid\r\n"
                        + "Authorization: Bearer top-secret\r\n"
                        + "Cookie: sid=session-secret\r\n"
                        + "Content-Length: 3\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1),
                requestBody);

        ApiRequest authored = new ApiRequest();
        authored.id = "request-id";
        authored.name = "=@formula";
        authored.method = "POST";
        authored.url = "https://example.invalid/upload?access_token=query-secret&ok=1";
        authored.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        authored.exactHttpRequest = new ExactHttpRequestSnapshot();
        authored.exactHttpRequest.rawRequestBytes = rawRequest.clone();
        authored.exactHttpRequest.pristine = true;

        HistoryRequestSnapshot request = HistoryRequestSnapshot.from(authored);
        request.rawRequestSent = rawRequest.clone();
        request.rawRequestSentText = null;
        request.resolvedUrl = authored.url;
        request.rawBodyTruncated = true;
        request.originalRawBodyLength = 10L;
        request.storedRawBodyLength = requestBody.length;
        request.fullRawBodySha256 = HistoryBodyTruncator.sha256Hex(new byte[10]);
        request.rawTruncationReason = "RAW_REQUEST_BODY_LIMIT";

        byte[] responseBody = new byte[]{0x10, 0x00, (byte) 0xfe};
        HistoryResponseSnapshot response = new HistoryResponseSnapshot();
        response.statusCode = 200;
        response.reasonPhrase = "OK";
        response.headers.add(new HistoryHeader("Content-Type", "application/octet-stream", false));
        response.headers.add(new HistoryHeader("Set-Cookie", "sid=response-secret; HttpOnly", false));
        response.body = responseBody.clone();
        response.bodyTruncated = true;
        response.originalBodyLength = 12L;
        response.storedBodyLength = responseBody.length;
        response.fullBodySha256 = HistoryBodyTruncator.sha256Hex(new byte[12]);
        response.truncationReason = "RESPONSE_BODY_LIMIT";

        HistoryEntry entry = new HistoryEntry();
        entry.id = "entry:1";
        entry.timestamp = Instant.parse("2026-07-05T01:00:00Z");
        entry.source = HistorySource.BURP_TRAFFIC;
        entry.collectionId = "collection-id";
        entry.collectionName = "Evidence Collection";
        entry.folderPath = "Captured";
        entry.requestId = authored.id;
        entry.requestName = authored.name;
        entry.requestSnapshot = request;
        entry.responseSnapshot = response;
        entry.statusCode = 200;
        entry.pinned = true;
        entry.tags = new LinkedHashSet<>(List.of("evidence", "binary"));
        entry.analystNotes = "Authorization: Bearer note-secret\naccess_token=note-secret\nkeep=visible";
        entry.metadataSummaryText = "url=" + authored.url;
        entry.ensureDefaults();
        return entry;
    }

    private static void assertDescriptor(JsonObject descriptor, byte[] bytes) {
        assertThat(descriptor.get("size").getAsLong()).isEqualTo(bytes.length);
        assertThat(descriptor.get("sha256").getAsString())
                .isEqualTo(HistoryBodyTruncator.sha256Hex(bytes));
    }

    private static Map<String, byte[]> readZipEntries(Path output) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(output), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                input.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    private static String headerText(byte[] message) {
        int boundary = boundaryIndex(message);
        int length = boundary >= 0 ? boundary : message.length;
        return new String(message, 0, length, StandardCharsets.ISO_8859_1);
    }

    private static byte[] bodyBytes(byte[] message) {
        int boundary = boundaryIndex(message);
        if (boundary < 0) {
            return new byte[0];
        }
        int separatorLength = message[boundary] == '\r' ? 4 : 2;
        return Arrays.copyOfRange(message, boundary + separatorLength, message.length);
    }

    private static int boundaryIndex(byte[] message) {
        for (int i = 0; i <= message.length - 4; i++) {
            if (message[i] == '\r' && message[i + 1] == '\n'
                    && message[i + 2] == '\r' && message[i + 3] == '\n') {
                return i;
            }
        }
        for (int i = 0; i <= message.length - 2; i++) {
            if (message[i] == '\n' && message[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
