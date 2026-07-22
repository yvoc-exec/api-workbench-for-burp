package burp.utils;

import burp.history.HistoryEntry;
import burp.models.WorkspaceState;
import burp.performance.MemoryHardeningFixtureFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void slowStoreMeasuresPendingDetachedRevisionsWithoutInferringPhysicalPages() throws Exception {
        SlowRecordingStore store = new SlowRecordingStore();
        WorkspaceStateService service = new WorkspaceStateService(store);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            for (int revision = 0; revision < 10; revision++) {
                String json = WorkspaceStateJson.toJson(MemoryHardeningFixtureFactory.workspace(0, 0))
                        + "\n" + revision;
                executor.submit(() -> {
                    store.pending.incrementAndGet();
                    store.maxPending.accumulateAndGet(store.pending.get(), Math::max);
                    try {
                        service.saveJson(json);
                    } finally {
                        store.pending.decrementAndGet();
                    }
                });
            }
            assertThat(store.firstWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            store.releaseWrites.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            store.releaseWrites.countDown();
            executor.shutdownNow();
        }

        assertThat(store.writeCount).isEqualTo(10);
        assertThat(store.maxPending.get()).isGreaterThan(1);
        assertThat(store.cumulativeExtensionDataBytesSubmitted)
                .isGreaterThan(store.currentExtensionDataValueBytes);
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
    void byteArrayJsonInflationIsComparedWithRawAndReferenceBase64() {
        byte[] raw = MemoryHardeningFixtureFactory.binaryBytes(64 * 1024);
        WorkspaceState state = MemoryHardeningFixtureFactory.workspace(0, 0);
        HistoryEntry entry = MemoryHardeningFixtureFactory.historyEntry(1, 64, 0);
        entry.responseSnapshot.body = raw;
        entry.responseSnapshot.originalBodyLength = raw.length;
        entry.responseSnapshot.storedBodyLength = raw.length;
        state.historyEntries.add(entry);
        int jsonBytes = WorkspaceStateJson.toJson(state).getBytes(StandardCharsets.UTF_8).length;
        int base64Bytes = MemoryHardeningFixtureFactory.referenceBase64Length(raw);

        assertThat(base64Bytes).isGreaterThan(raw.length);
        assertThat(jsonBytes).isGreaterThan(base64Bytes);
    }

    @Test
    void previousFullJsonRetentionProxyEqualsCurrentSerializedValue() {
        RecordingStore store = new RecordingStore();
        WorkspaceStateService service = new WorkspaceStateService(store);
        service.save(MemoryHardeningFixtureFactory.workspace(1, 2048));
        String lastSavedWorkspaceJson = store.currentValue;

        assertThat(lastSavedWorkspaceJson.getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(store.currentExtensionDataValueBytes);
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

    private static final class SlowRecordingStore extends RecordingStore {
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch releaseWrites = new CountDownLatch(1);
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger maxPending = new AtomicInteger();

        @Override
        public void set(String key, String value) {
            firstWriteStarted.countDown();
            try {
                releaseWrites.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            super.set(key, value);
        }
    }
}
