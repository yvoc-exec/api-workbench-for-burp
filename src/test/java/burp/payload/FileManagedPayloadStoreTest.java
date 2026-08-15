package burp.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileManagedPayloadStoreTest {
    @TempDir Path temp;

    @Test
    void stagesDeduplicatesReadsSlicesAndCleansCancellation() throws Exception {
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            byte[] bytes = "headers-body-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ManagedPayloadRef first = commit(store, bytes);
            ManagedPayloadRef second = commit(store, bytes);

            assertThat(first).isEqualTo(second);
            assertThat(store.uniqueBlobCount()).isEqualTo(1);
            assertThat(store.openSlice(new PayloadSliceRef(first, 8, 4)).readAllBytes())
                    .isEqualTo("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            try (ManagedPayloadStage abandoned = store.beginStage("cancel")) {
                abandoned.write(new byte[]{1, 2, 3});
            }
            assertThat(Files.list(temp.resolve("store/staging"))).isEmpty();
        }
    }

    @Test
    void activeLeaseProtectsBlobAndSuccessfulManifestRevisionAllowsSweep() throws Exception {
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            ManagedPayloadRef retained = commit(store, new byte[]{1, 2, 3});
            ManagedPayloadRef orphan = commit(store, new byte[]{4, 5, 6});
            try (ManagedPayloadLease lease = store.materialize(orphan)) {
                store.updateManifest(1, Set.of(retained.payloadId));
                store.sweepUnreferenced(Set.of(retained.payloadId), Set.of());
                assertThat(store.exists(orphan)).isTrue();
            }
            store.sweepUnreferenced(store.loadManifestPayloadIds(), Set.of());
            assertThat(store.exists(retained)).isTrue();
            assertThat(store.exists(orphan)).isFalse();
        }
    }

    @Test
    void corruptionFailsClosed() throws Exception {
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(temp.resolve("store"))) {
            ManagedPayloadRef ref = commit(store, new byte[]{1, 2, 3, 4});
            Path blob = temp.resolve("store/blobs").resolve(ref.payloadId.substring(0, 2)).resolve(ref.payloadId);
            Files.write(blob, new byte[]{4, 3, 2, 1});

            assertThatThrownBy(() -> store.materialize(ref)).isInstanceOf(IOException.class)
                    .hasMessageContaining("corrupted");
        }
    }

    @Test
    void restartReconcilesManifestFromThePersistedWorkspaceRevision() throws Exception {
        Path root = temp.resolve("store");
        ManagedPayloadRef first;
        ManagedPayloadRef second;
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(root)) {
            first = commit(store, new byte[]{1, 2, 3});
            second = commit(store, new byte[]{4, 5, 6});
            store.updateManifest(42, Set.of(first.payloadId));
        }

        try (FileManagedPayloadStore reopened = new FileManagedPayloadStore(root)) {
            reopened.updateManifest(0, Set.of(second.payloadId));
            reopened.sweepUnreferenced(reopened.loadManifestPayloadIds(), Set.of());

            assertThat(reopened.exists(first)).isFalse();
            assertThat(reopened.exists(second)).isTrue();
            assertThat(reopened.loadManifestPayloadIds()).containsExactly(second.payloadId);
        }
    }

    @Test
    void corruptManifestDoesNotDeleteBlobsBeforeWorkspaceReconciliation() throws Exception {
        Path root = temp.resolve("store");
        ManagedPayloadRef ref;
        try (FileManagedPayloadStore store = new FileManagedPayloadStore(root)) {
            ref = commit(store, new byte[]{7, 8, 9});
        }
        Files.createDirectories(root.resolve("manifest"));
        Files.writeString(root.resolve("manifest/current.json"), "{not-json");

        try (FileManagedPayloadStore reopened = new FileManagedPayloadStore(root)) {
            assertThat(reopened.exists(ref)).isTrue();
            reopened.updateManifest(0, Set.of(ref.payloadId));
            assertThat(reopened.loadManifestPayloadIds()).containsExactly(ref.payloadId);
        }
    }

    private static ManagedPayloadRef commit(FileManagedPayloadStore store, byte[] bytes) throws Exception {
        try (ManagedPayloadStage stage = store.beginStage("test")) {
            stage.write(bytes);
            return store.commit(stage);
        }
    }
}
