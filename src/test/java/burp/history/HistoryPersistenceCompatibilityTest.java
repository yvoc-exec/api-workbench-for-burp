package burp.history;

import burp.models.RedirectHop;
import burp.models.RedirectTerminationReason;
import burp.models.WorkspaceState;
import burp.testsupport.TestResourceLoader;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryPersistenceCompatibilityTest {

    @Test
    void oldHistoryJsonRestoresDefaults() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/legacy-history.json"));

        assertThat(state.version).isEqualTo(2);
        assertThat(state.historyRetentionPolicy).isNotNull();
        assertThat(state.historyRetentionPolicy.maxEntries).isEqualTo(1000);
        assertThat(state.historyRetentionPolicy.maxTotalStoredBytes).isEqualTo(100L * 1024L * 1024L);
        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.pinned).isFalse();
        assertThat(entry.analystNotes).isEmpty();
        assertThat(entry.tags).isEmpty();
        assertThat(entry.requestSnapshot.bodyTruncated).isFalse();
        assertThat(entry.requestSnapshot.originalBodyLength).isZero();
        assertThat(entry.requestSnapshot.storedBodyLength).isZero();
        assertThat(entry.requestSnapshot.fullBodySha256).isEmpty();
    }

    @Test
    void currentHistorySchemaLoadsNewestFirstAndPreservesRawSentEvidence() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/current-history.json"));

        assertThat(state.historyEntries).extracting(entry -> entry.id)
                .containsExactly("hist-current-2", "hist-current-1");

        HistoryEntry rawEntry = state.historyEntries.get(1);
        assertThat(rawEntry.requestSnapshot.hasRawRequestSent()).isTrue();
        assertThat(rawEntry.requestSnapshot.preferredRawRequestText()).contains("POST /login HTTP/1.1");
        assertThat(rawEntry.requestSnapshot.toApiRequest().method).isEqualTo("POST");
        assertThat(rawEntry.requestSnapshot.toApiRequest().url).isEqualTo("{{base_url}}/login");
    }

    @Test
    void legacyHistoryEntriesWithoutRawRequestStillLoadAndRebuildRequestSafely() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/legacy-history.json"));

        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.requestSnapshot.hasRawRequestSent()).isFalse();
        assertThat(entry.requestSnapshot.preferredRawRequestText()).isEmpty();
        assertThat(entry.requestSnapshot.toApiRequest().method).isEqualTo("POST");
        assertThat(entry.requestSnapshot.toApiRequest().url).isEqualTo("{{base_url}}/legacy-login");
    }

    @Test
    void futureFieldsAndMissingCollectionsOrListsLoadWithoutThrowing() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/future-history.json"));

        assertThat(state.version).isGreaterThanOrEqualTo(2);
        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.result).isEqualTo(HistoryResult.UNKNOWN);
        assertThat(entry.requestSnapshot).isNotNull();
        assertThat(entry.responseSnapshot).isNotNull();
        assertThat(entry.assertions).isNotNull().isEmpty();
        assertThat(entry.scriptLogs).isNotNull().isEmpty();
        assertThat(entry.scriptWarnings).isNotNull().isEmpty();
        assertThat(entry.scriptErrors).isNotNull().isEmpty();
    }

    @Test
    void duplicateIdsRemainDeterministicallyDeduplicated() {
        WorkspaceState state = WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/duplicate-history.json"));
        assertThat(state.historyEntries).hasSize(1);
        assertThat(state.historyEntries.get(0).id).isEqualTo("hist-dup");
        assertThat(state.historyEntries.get(0).requestSnapshot.urlTemplate).isEqualTo("https://api.example.test/new");

        HistoryStore store = new HistoryStore();
        store.setRetentionPolicy(new HistoryRetentionPolicy(1));
        new HistoryPersistenceService().restoreStore(store, state);
        assertThat(store.snapshot()).hasSize(1);
        assertThat(store.snapshot().get(0).id).isEqualTo("hist-dup");
    }

    @Test
    void oldRetentionPolicyRestoresSafeDefaults() {
        WorkspaceState state = WorkspaceStateJson.fromJson("""
                {
                  "version": 1,
                  "historyRetentionPolicy": {
                    "maxEntries": 0,
                    "maxTotalStoredBytes": 0,
                    "maxRequestBodyBytesPerEntry": 0,
                    "maxResponseBodyBytesPerEntry": 0,
                    "retainPinnedEntries": true
                  },
                  "historyEntries": []
                }
                """);

        assertThat(state.historyRetentionPolicy).isNotNull();
        assertThat(state.historyRetentionPolicy.maxEntries).isEqualTo(1000);
        assertThat(state.historyRetentionPolicy.maxTotalStoredBytes).isEqualTo(100L * 1024L * 1024L);
        assertThat(state.historyRetentionPolicy.maxRequestBodyBytesPerEntry).isEqualTo(1L * 1024L * 1024L);
        assertThat(state.historyRetentionPolicy.maxResponseBodyBytesPerEntry).isEqualTo(2L * 1024L * 1024L);
        assertThat(state.historyRetentionPolicy.retainPinnedEntries).isTrue();
    }

    @Test
    void corruptAndTruncatedHistoryJsonFailFast() {
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/corrupt-history.json")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> WorkspaceStateJson.fromJson(TestResourceLoader.read("fixtures/history/truncated-history.json")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void historyPersistenceServiceRoundTripsRedirectEvidenceAndBytes() {
        HistoryEntry entry = new HistoryEntry();
        entry.id = "hist-redirect";
        entry.timestamp = Instant.parse("2026-06-15T01:30:00Z");
        entry.requestName = "Login";
        entry.requestId = "req-login";
        entry.collectionName = "APIM";
        entry.folderPath = "Auth";
        entry.redirectsEnabled = true;
        entry.initialResolvedUrl = "https://api.example.test/start";
        entry.finalResolvedUrl = "https://api.example.test/final";
        entry.redirectTerminationReason = RedirectTerminationReason.FINAL_RESPONSE;
        entry.requestSnapshot = new HistoryRequestSnapshot();
        entry.requestSnapshot.method = "POST";
        entry.requestSnapshot.urlTemplate = "https://api.example.test/start";
        entry.requestSnapshot.rawRequestSent = "POST /start HTTP/1.1\r\nHost: api.example.test\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);
        entry.requestSnapshot.rawRequestSentText = new String(entry.requestSnapshot.rawRequestSent, StandardCharsets.UTF_8);
        entry.responseSnapshot = new HistoryResponseSnapshot();
        entry.responseSnapshot.statusCode = 200;
        entry.responseSnapshot.reasonPhrase = "OK";
        entry.responseSnapshot.body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        entry.responseSnapshot.mimeType = "application/json";
        RedirectHop followed = new RedirectHop();
        followed.hopNumber = 1;
        followed.sourceUrl = "https://api.example.test/start";
        followed.targetUrl = "https://api.example.test/next";
        followed.statusCode = 302;
        followed.location = "/next";
        followed.followed = true;
        followed.rawRequestBytes = "GET /next HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        followed.rawRequestText = new String(followed.rawRequestBytes, StandardCharsets.UTF_8);
        followed.responseBody = "{\"step\":1}".getBytes(StandardCharsets.UTF_8);
        followed.forwardedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Authorization"));
        followed.strippedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Proxy-Authorization"));
        RedirectHop blocked = new RedirectHop();
        blocked.hopNumber = 2;
        blocked.sourceUrl = "https://api.example.test/next";
        blocked.targetUrl = "https://api.example.test/final";
        blocked.statusCode = 307;
        blocked.location = "/final";
        blocked.followed = false;
        blocked.failureReason = "Redirects disabled";
        blocked.rawRequestBytes = "POST /final HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        blocked.rawRequestText = new String(blocked.rawRequestBytes, StandardCharsets.UTF_8);
        blocked.responseBody = "{\"step\":2}".getBytes(StandardCharsets.UTF_8);
        blocked.forwardedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Cookie"));
        blocked.strippedSensitiveHeaderNames = new java.util.ArrayList<>(List.of("Proxy-Authorization"));
        entry.redirectHops = new java.util.ArrayList<>(List.of(followed, blocked));
        HistoryBodyTruncator.apply(entry, new HistoryRetentionPolicy(100, 10_000L, 4L, 5L, true));

        HistoryPersistenceService service = new HistoryPersistenceService();
        WorkspaceState state = new WorkspaceState();
        service.writeHistory(state, List.of(entry));
        WorkspaceState restored = WorkspaceStateJson.fromJson(WorkspaceStateJson.toJson(state));
        HistoryStore store = new HistoryStore();
        service.restoreStore(store, restored);

        assertThat(store.snapshot()).hasSize(1);
        HistoryEntry reloaded = store.snapshot().get(0);
        assertThat(reloaded.id).isEqualTo(entry.id);
        assertThat(reloaded.redirectsEnabled).isTrue();
        assertThat(reloaded.initialResolvedUrl).isEqualTo(entry.initialResolvedUrl);
        assertThat(reloaded.finalResolvedUrl).isEqualTo(entry.finalResolvedUrl);
        assertThat(reloaded.redirectTerminationReason).isEqualTo(RedirectTerminationReason.FINAL_RESPONSE);
        assertThat(reloaded.redirectHops).hasSize(2);
        assertThat(reloaded.redirectHops.get(0).followed).isTrue();
        assertThat(reloaded.redirectHops.get(1).followed).isFalse();
        assertThat(reloaded.redirectHops.get(0).rawRequestBytes).isEqualTo(followed.rawRequestBytes);
        assertThat(reloaded.redirectHops.get(1).rawRequestBytes).isEqualTo(blocked.rawRequestBytes);
        assertThat(reloaded.redirectHops.get(0).rawRequestBodyTruncated).isFalse();
        assertThat(reloaded.redirectHops.get(0).originalRawRequestBodyLength).isZero();
        assertThat(reloaded.redirectHops.get(0).storedRawRequestBodyLength).isZero();
        assertThat(reloaded.redirectHops.get(0).responseBodyTruncated).isTrue();
        assertThat(reloaded.redirectHops.get(0).storedResponseBodyLength).isEqualTo(5L);
        assertThat(reloaded.redirectHops.get(0).fullResponseBodySha256).isEqualTo(entry.redirectHops.get(0).fullResponseBodySha256);
        assertThat(reloaded.requestSnapshot.rawRequestSent).isEqualTo(entry.requestSnapshot.rawRequestSent);
        assertThat(reloaded.responseSnapshot.body).isEqualTo(entry.responseSnapshot.body);
        assertThat(reloaded.redirectHops.get(0).forwardedSensitiveHeaderNames).containsExactly("Authorization");
        assertThat(reloaded.redirectHops.get(0).strippedSensitiveHeaderNames).containsExactly("Proxy-Authorization");
        assertThat(reloaded.redirectHops.get(1).forwardedSensitiveHeaderNames).containsExactly("Cookie");
        assertThat(reloaded.redirectHops.get(1).strippedSensitiveHeaderNames).containsExactly("Proxy-Authorization");
        assertThat(restored.version).isEqualTo(2);
    }

    @Test
    void legacyHistoryFixtureWithoutRedirectFieldsLoadsWithSafeDefaults() {
        String json = """
                {
                  "version": 1,
                  "historyEntries": [{
                    "id": "legacy",
                    "timestamp": "2026-06-15T01:30:00Z",
                    "source": "WORKBENCH",
                    "requestSnapshot": {
                      "method": "GET",
                      "urlTemplate": "https://api.example.test/legacy",
                      "rawRequestSentText": "GET /legacy HTTP/1.1\\r\\nHost: api.example.test\\r\\n\\r\\n"
                    }
                  }]
                }
                """;

        WorkspaceState state = WorkspaceStateJson.fromJson(json);
        assertThat(state.historyEntries).hasSize(1);
        HistoryEntry entry = state.historyEntries.get(0);
        assertThat(entry.redirectsEnabled).isNull();
        entry.ensureDefaults();
        assertThat(entry.redirectHops).isNotNull().isEmpty();
        assertThat(entry.redirectTerminationReason).isEqualTo(RedirectTerminationReason.NONE);
    }
}
