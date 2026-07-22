package burp.utils;

import burp.history.HistoryEntry;
import burp.history.HistoryJsonSupport;
import burp.models.WorkspaceState;
import burp.performance.MemoryHardeningFixtureFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceProjectStorageAttributionTest {
    private static final String PHYSICAL_PROJECT_DISCLAIMER =
            "Burp project physical page reclamation is outside this test harness. "
                    + "These values attribute extension-submitted volume, not exact project-file bytes.";

    @Test
    void recordingStoreAttributesSmallHeavyRepeatedAndRevisionWrites() {
        RecordingStore store = new RecordingStore();
        WorkspaceStateService service = new WorkspaceStateService(store);

        service.save(MemoryHardeningFixtureFactory.workspace(0, 0));
        int smallBytes = store.currentExtensionDataValueBytes;
        service.save(MemoryHardeningFixtureFactory.workspace(2, 32 * 1024));
        int heavyBytes = store.currentExtensionDataValueBytes;
        String unchanged = store.currentValue;
        service.saveJson(unchanged);
        service.saveJson(unchanged);
        for (int revision = 0; revision < 10; revision++) {
            WorkspaceState state = MemoryHardeningFixtureFactory.workspace(0, 0);
            state.selectedRequestName = "revision-" + revision;
            service.save(state);
        }

        assertThat(smallBytes).isPositive();
        assertThat(heavyBytes).isGreaterThan(smallBytes);
        assertThat(store.writeCount).isEqualTo(14);
        assertThat(store.cumulativeExtensionDataBytesSubmitted)
                .isGreaterThan(store.currentExtensionDataValueBytes);
        assertThat(store.maximumSingleValueSize).isGreaterThanOrEqualTo(heavyBytes);
        assertThat(store.consecutiveIdenticalValuesSubmitted).isEqualTo(2);
        assertThat(store.sha256Values).hasSize(store.writeCount).doesNotContainNull();
        assertThat(PHYSICAL_PROJECT_DISCLAIMER).contains("not exact project-file bytes");
    }

    @Test
    void historyExactEvidenceAndFidelityMetadataAreIncludedInWorkspaceJson() {
        WorkspaceState state = MemoryHardeningFixtureFactory.workspace(1, 4096);
        HistoryEntry entry = state.historyEntries.get(0);
        state.collections.get(0).requests.get(0).exactHttpRequest =
                MemoryHardeningFixtureFactory.exactSnapshot(4096);
        String json = WorkspaceStateJson.toJson(state);

        assertThat(json).contains("rawRequestSent", "sourceMetadata", "exactHttpRequest");
        assertThat(entry.requestSnapshot.rawRequestSent).hasSize(256);
        assertThat(json.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(entry.requestSnapshot.rawRequestSent.length + entry.responseSnapshot.body.length);
    }

    @Test
    void isolatedByteArrayJsonInflationIsComparedWithRawAndReferenceBase64() {
        byte[] raw = MemoryHardeningFixtureFactory.binaryBytes(64 * 1024);
        int jsonBytes = HistoryJsonSupport.createGson().toJson(raw).getBytes(StandardCharsets.UTF_8).length;
        int base64Bytes = MemoryHardeningFixtureFactory.referenceBase64Length(raw);

        assertThat(base64Bytes).isGreaterThan(raw.length);
        assertThat(jsonBytes).isGreaterThan(base64Bytes);
        assertThat((double) jsonBytes / raw.length).isGreaterThan(1.0);
    }

    static class RecordingStore implements WorkspaceStateService.StringStore {
        String key;
        int writeCount;
        long cumulativeExtensionDataBytesSubmitted;
        int maximumSingleValueSize;
        int currentExtensionDataValueBytes;
        int consecutiveIdenticalValuesSubmitted;
        String currentValue;
        final List<Integer> bytesPerWrite = new ArrayList<>();
        final List<String> sha256Values = new ArrayList<>();

        @Override
        public synchronized String get(String key) {
            return currentValue;
        }

        @Override
        public synchronized void set(String key, String value) {
            String safe = value != null ? value : "";
            int bytes = safe.getBytes(StandardCharsets.UTF_8).length;
            if (safe.equals(currentValue)) {
                consecutiveIdenticalValuesSubmitted++;
            }
            this.key = key;
            writeCount++;
            cumulativeExtensionDataBytesSubmitted += bytes;
            maximumSingleValueSize = Math.max(maximumSingleValueSize, bytes);
            currentExtensionDataValueBytes = bytes;
            bytesPerWrite.add(bytes);
            sha256Values.add(sha256(safe));
            currentValue = safe;
        }

        private static String sha256(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

}
