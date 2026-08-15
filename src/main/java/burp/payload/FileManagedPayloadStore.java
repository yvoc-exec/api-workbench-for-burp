package burp.payload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class FileManagedPayloadStore implements ManagedPayloadStore {
    public static final int CHUNK_SIZE = 1024 * 1024;
    private static final int MANIFEST_VERSION = 1;

    private final Path root;
    private final Path blobs;
    private final Path staging;
    private final Path manifest;
    private final Object lock = new Object();
    private final Map<String, Integer> activeLeases = new HashMap<>();
    private final Map<String, VerifiedBlob> verifiedBlobs = new HashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private long lastManifestRevision = -1L;
    private boolean closed;

    public FileManagedPayloadStore(Path root) throws IOException {
        if (root == null) throw new IllegalArgumentException("Managed payload root is required.");
        this.root = root.toAbsolutePath().normalize();
        this.blobs = this.root.resolve("blobs");
        this.staging = this.root.resolve("staging");
        this.manifest = this.root.resolve("manifest").resolve("current.json");
        createDirectory(this.root, true);
        createDirectory(blobs, true);
        createDirectory(staging, true);
        createDirectory(manifest.getParent(), true);
        cleanupStaging();
    }

    public Path root() {
        return root;
    }

    @Override
    public ManagedPayloadStage beginStage(String operation) throws IOException {
        synchronized (lock) {
            ensureOpen();
            Path part = staging.resolve(UUID.randomUUID() + ".part");
            ManagedPayloadStage stage = new ManagedPayloadStage(part);
            secureFile(part);
            return stage;
        }
    }

    @Override
    public ManagedPayloadRef commit(ManagedPayloadStage stage) throws IOException {
        if (stage == null) throw new IllegalArgumentException("Managed payload stage is required.");
        synchronized (lock) {
            ensureOpen();
            String sha = stage.finish();
            ManagedPayloadRef ref = new ManagedPayloadRef(sha, stage.length(), sha);
            Path target = blobPath(ref);
            createDirectory(target.getParent(), true);
            if (Files.exists(target)) {
                if (Files.size(target) != ref.length) {
                    throw new IOException("Managed payload integrity check failed for existing blob.");
                }
                verify(ref);
                Files.deleteIfExists(stage.temporaryFile());
            } else {
                try {
                    Files.move(stage.temporaryFile(), target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(stage.temporaryFile(), target);
                }
                secureFile(target);
                rememberVerified(ref, target);
            }
            stage.markCommitted();
            return ref;
        }
    }

    @Override
    public InputStream open(ManagedPayloadRef ref) throws IOException {
        verify(ref);
        return Files.newInputStream(blobPath(ref));
    }

    @Override
    public InputStream openSlice(PayloadSliceRef slice) throws IOException {
        if (slice == null) throw new IllegalArgumentException("Managed payload slice is required.");
        slice.validate();
        InputStream input = open(slice.payload);
        try {
            skipFully(input, slice.offset);
            return new LimitedInputStream(input, slice.length);
        } catch (IOException e) {
            input.close();
            throw e;
        }
    }

    @Override
    public ManagedPayloadLease materialize(ManagedPayloadRef ref) throws IOException {
        ref.validate();
        return materialize(ref, 0L, ref.length);
    }

    @Override
    public ManagedPayloadLease materialize(PayloadSliceRef slice) throws IOException {
        if (slice == null) throw new IllegalArgumentException("Managed payload slice is required.");
        slice.validate();
        return materialize(slice.payload, slice.offset, slice.length);
    }

    private ManagedPayloadLease materialize(ManagedPayloadRef ref, long offset, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("Managed payload exceeds the Java transport representation limit.");
        }
        retainLease(ref.payloadId);
        boolean success = false;
        try (InputStream input = offset == 0L && length == ref.length
                ? open(ref) : openSlice(new PayloadSliceRef(ref, offset, length))) {
            byte[] bytes = new byte[(int) length];
            int position = 0;
            while (position < bytes.length) {
                int count = input.read(bytes, position, bytes.length - position);
                if (count < 0) throw new EOFException("Managed payload ended before its declared length.");
                position += count;
            }
            if (input.read() != -1) throw new IOException("Managed payload exceeds its declared length.");
            success = true;
            return new ManagedPayloadLease(bytes, () -> releaseLease(ref.payloadId));
        } finally {
            if (!success) releaseLease(ref.payloadId);
        }
    }

    @Override
    public boolean exists(ManagedPayloadRef ref) {
        try {
            ref.validate();
            Path path = blobPath(ref);
            return Files.isRegularFile(path) && Files.size(path) == ref.length;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    @Override
    public void verify(ManagedPayloadRef ref) throws IOException {
        validateAvailable(ref);
        Path path = blobPath(ref);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        synchronized (lock) {
            VerifiedBlob verified = verifiedBlobs.get(ref.payloadId);
            if (verified != null && verified.matches(ref, attributes)) return;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
        byte[] buffer = new byte[CHUNK_SIZE];
        long length = 0L;
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                digest.update(buffer, 0, count);
                length += count;
            }
        }
        String actual = hex(digest.digest());
        if (length != ref.length || !actual.equals(ref.sha256)) {
            throw new IOException("Managed payload is unavailable or corrupted in the local managed payload store.");
        }
        BasicFileAttributes verifiedAttributes = Files.readAttributes(path, BasicFileAttributes.class);
        synchronized (lock) {
            verifiedBlobs.put(ref.payloadId,
                    new VerifiedBlob(verifiedAttributes.size(), verifiedAttributes.lastModifiedTime()));
        }
    }

    @Override
    public void cleanupStaging() throws IOException {
        if (!Files.isDirectory(staging)) return;
        try (Stream<Path> files = Files.list(staging)) {
            for (Path path : files.toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".part")) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Override
    public void sweepUnreferenced(Set<String> livePayloadIds, Set<String> leasedPayloadIds) throws IOException {
        Set<String> retained = new HashSet<>();
        if (livePayloadIds != null) retained.addAll(livePayloadIds);
        if (leasedPayloadIds != null) retained.addAll(leasedPayloadIds);
        retained.addAll(activeLeasePayloadIds());
        if (!Files.isDirectory(blobs)) return;
        try (Stream<Path> files = Files.walk(blobs)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (!retained.contains(path.getFileName().toString())) {
                    Files.deleteIfExists(path);
                    synchronized (lock) {
                        verifiedBlobs.remove(path.getFileName().toString());
                    }
                }
            }
        }
        try (Stream<Path> dirs = Files.walk(blobs)) {
            List<Path> ordered = new ArrayList<>(dirs.filter(Files::isDirectory).toList());
            Collections.reverse(ordered);
            for (Path dir : ordered) {
                if (!dir.equals(blobs) && isEmpty(dir)) Files.deleteIfExists(dir);
            }
        }
    }

    @Override
    public void updateManifest(long revision, Set<String> livePayloadIds) throws IOException {
        if (revision < 0L) throw new IllegalArgumentException("Manifest revision must be non-negative.");
        synchronized (lock) {
            ensureOpen();
            if (lastManifestRevision > revision) return;
            Manifest next = new Manifest();
            next.version = MANIFEST_VERSION;
            next.revision = revision;
            next.payloadIds = livePayloadIds != null
                    ? livePayloadIds.stream().filter(ManagedPayloadRef::isSha256).sorted().toList()
                    : List.of();
            Path temporary = manifest.resolveSibling("current-" + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temporary, gson.toJson(next));
                secureFile(temporary);
                try {
                    Files.move(temporary, manifest,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            lastManifestRevision = revision;
        }
    }

    @Override
    public Set<String> loadManifestPayloadIds() throws IOException {
        synchronized (lock) {
            Manifest value = readManifest();
            return value == null || value.payloadIds == null
                    ? Set.of() : Set.copyOf(value.payloadIds);
        }
    }

    @Override
    public Set<String> activeLeasePayloadIds() {
        synchronized (lock) {
            return Set.copyOf(activeLeases.keySet());
        }
    }

    @Override
    public long physicalBlobBytes() throws IOException {
        if (!Files.isDirectory(blobs)) return 0L;
        try (Stream<Path> files = Files.walk(blobs)) {
            long total = 0L;
            for (Path path : files.filter(Files::isRegularFile).toList()) total = Math.addExact(total, Files.size(path));
            return total;
        }
    }

    @Override
    public int uniqueBlobCount() throws IOException {
        if (!Files.isDirectory(blobs)) return 0;
        try (Stream<Path> files = Files.walk(blobs)) {
            return Math.toIntExact(files.filter(Files::isRegularFile).count());
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            cleanupStaging();
            activeLeases.clear();
            verifiedBlobs.clear();
        }
    }

    private void rememberVerified(ManagedPayloadRef ref, Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        verifiedBlobs.put(ref.payloadId,
                new VerifiedBlob(attributes.size(), attributes.lastModifiedTime()));
    }

    private void validateAvailable(ManagedPayloadRef ref) throws IOException {
        if (!exists(ref)) {
            throw new IOException("Exact payload is unavailable or corrupted in the local managed payload store.");
        }
    }

    private Path blobPath(ManagedPayloadRef ref) {
        ref.validate();
        return blobs.resolve(ref.payloadId.substring(0, 2)).resolve(ref.payloadId);
    }

    private void retainLease(String payloadId) {
        synchronized (lock) {
            activeLeases.merge(payloadId, 1, Integer::sum);
        }
    }

    private void releaseLease(String payloadId) {
        synchronized (lock) {
            activeLeases.computeIfPresent(payloadId, (ignored, count) -> count <= 1 ? null : count - 1);
        }
    }

    private Manifest readManifest() throws IOException {
        if (!Files.isRegularFile(manifest)) return null;
        Manifest value = gson.fromJson(Files.readString(manifest), Manifest.class);
        if (value == null || value.version != MANIFEST_VERSION || value.revision < 0L) {
            throw new IOException("Managed payload manifest is invalid.");
        }
        return value;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Managed payload store is closed.");
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
            } else if (input.read() < 0) {
                throw new EOFException("Managed payload slice starts beyond available content.");
            } else {
                remaining--;
            }
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void createDirectory(Path directory, boolean ownerOnly) throws IOException {
        Files.createDirectories(directory);
        if (ownerOnly) secureDirectory(directory);
    }

    private static void secureDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // User-local Windows ACLs or a non-POSIX filesystem remain authoritative.
        }
    }

    private static void secureFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // User-local Windows ACLs or a non-POSIX filesystem remain authoritative.
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    private static final class Manifest {
        int version;
        long revision;
        List<String> payloadIds = List.of();
    }

    private record VerifiedBlob(long length, FileTime lastModified) {
        boolean matches(ManagedPayloadRef ref, BasicFileAttributes attributes) {
            return ref != null && length == ref.length && attributes.size() == length
                    && lastModified.equals(attributes.lastModifiedTime());
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0L) return -1;
            int value = super.read();
            if (value < 0) throw new EOFException("Managed payload slice ended early.");
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0L) return -1;
            int requested = (int) Math.min(length, remaining);
            int count = super.read(bytes, offset, requested);
            if (count < 0) throw new EOFException("Managed payload slice ended early.");
            remaining -= count;
            return count;
        }
    }
}
