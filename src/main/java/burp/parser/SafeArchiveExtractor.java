package burp.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SafeArchiveExtractor {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private SafeArchiveExtractor() {
    }

    public static void extract(Path zipPath, Path targetDir, ArchiveImportLimits limits) throws IOException {
        ArchiveImportLimits safeLimits = ArchiveImportLimits.copyOf(limits);
        Path normalizedRoot = targetDir != null ? targetDir.toAbsolutePath().normalize() : null;
        if (zipPath == null || normalizedRoot == null) {
            throw new IOException("Archive import rejected: target path not configured.");
        }
        Files.createDirectories(normalizedRoot);
        Set<String> collisionKeys = new HashSet<>();
        long totalUncompressed = 0L;
        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > safeLimits.maxEntries) {
                    throw new ArchiveImportLimitException(
                            ArchiveImportLimitException.Reason.ENTRY_COUNT,
                            entry.getName(),
                            entryCount,
                            safeLimits.maxEntries
                    );
                }
                NormalizedEntry normalized = normalizeEntry(entry, normalizedRoot, safeLimits);
                String collisionKey = normalized.collisionKey();
                if (!collisionKeys.add(collisionKey)) {
                    throw new ArchiveImportLimitException(
                            ArchiveImportLimitException.Reason.DUPLICATE_OUTPUT_PATH,
                            normalized.normalizedName(),
                            0,
                            0
                    );
                }
                Path outputPath = normalized.outputPath();
                if (normalized.directory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }

                long declaredSize = entry.getSize();
                if (declaredSize > safeLimits.maxEntryUncompressedBytes) {
                    throw new ArchiveImportLimitException(
                            ArchiveImportLimitException.Reason.ENTRY_UNCOMPRESSED_BYTES,
                            normalized.normalizedName(),
                            declaredSize,
                            safeLimits.maxEntryUncompressedBytes
                    );
                }
                maybeRejectCompressionRatio(entry, declaredSize, safeLimits, normalized.normalizedName());

                Path parent = outputPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                long actualBytes = 0L;
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int read;
                    while ((read = zin.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        actualBytes += read;
                        if (actualBytes > safeLimits.maxEntryUncompressedBytes) {
                            throw new ArchiveImportLimitException(
                                    ArchiveImportLimitException.Reason.ENTRY_UNCOMPRESSED_BYTES,
                                    normalized.normalizedName(),
                                    actualBytes,
                                    safeLimits.maxEntryUncompressedBytes
                            );
                        }
                        totalUncompressed += read;
                        if (totalUncompressed > safeLimits.maxTotalUncompressedBytes) {
                            throw new ArchiveImportLimitException(
                                    ArchiveImportLimitException.Reason.TOTAL_UNCOMPRESSED_BYTES,
                                    normalized.normalizedName(),
                                    totalUncompressed,
                                    safeLimits.maxTotalUncompressedBytes
                            );
                        }
                        out.write(buffer, 0, read);
                    }
                }
                maybeRejectCompressionRatio(entry, actualBytes, safeLimits, normalized.normalizedName());
            }
        } catch (IOException e) {
            cleanupExtractionTree(normalizedRoot);
            throw e;
        } catch (RuntimeException e) {
            cleanupExtractionTree(normalizedRoot);
            throw e;
        }
    }

    static NormalizedEntry normalizeEntry(ZipEntry entry, Path normalizedRoot, ArchiveImportLimits limits) throws ArchiveImportLimitException {
        String rawName = entry != null ? entry.getName() : null;
        String normalizedName = normalizeEntryName(rawName);
        if (normalizedName.isBlank()) {
            throw new ArchiveImportLimitException(
                    ArchiveImportLimitException.Reason.UNSAFE_PATH,
                    rawName,
                    0,
                    0
            );
        }
        String[] segments = normalizedName.split("/");
        List<String> cleanSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new ArchiveImportLimitException(
                        ArchiveImportLimitException.Reason.UNSAFE_PATH,
                        normalizedName,
                        0,
                        0
                );
            }
            cleanSegments.add(segment);
        }
        if (cleanSegments.isEmpty()) {
            throw new ArchiveImportLimitException(
                    ArchiveImportLimitException.Reason.UNSAFE_PATH,
                    normalizedName,
                    0,
                    0
            );
        }
        if (cleanSegments.size() > limits.maxPathDepth) {
            throw new ArchiveImportLimitException(
                    ArchiveImportLimitException.Reason.PATH_DEPTH,
                    normalizedName,
                    cleanSegments.size(),
                    limits.maxPathDepth
            );
        }
        Path relative = Paths.get(cleanSegments.get(0), cleanSegments.subList(1, cleanSegments.size()).toArray(String[]::new)).normalize();
        Path output = normalizedRoot.resolve(relative).normalize();
        if (!output.startsWith(normalizedRoot)) {
            throw new ArchiveImportLimitException(
                    ArchiveImportLimitException.Reason.UNSAFE_PATH,
                    normalizedName,
                    0,
                    0
            );
        }
        String collisionKey = output.toString().toLowerCase(Locale.ROOT);
        return new NormalizedEntry(normalizedName, output, entry != null && entry.isDirectory(), collisionKey);
    }

    static String normalizeEntryName(String rawName) throws ArchiveImportLimitException {
        if (rawName == null) {
            throw new ArchiveImportLimitException(ArchiveImportLimitException.Reason.UNSAFE_PATH, null, 0, 0);
        }
        String normalized = rawName.replace('\\', '/');
        if (normalized.isBlank() || normalized.indexOf('\0') >= 0) {
            throw new ArchiveImportLimitException(ArchiveImportLimitException.Reason.UNSAFE_PATH, rawName, 0, 0);
        }
        String trimmed = normalized.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.matches("^[A-Za-z]:.*")) {
            throw new ArchiveImportLimitException(ArchiveImportLimitException.Reason.UNSAFE_PATH, rawName, 0, 0);
        }
        return trimmed;
    }

    private static void maybeRejectCompressionRatio(ZipEntry entry,
                                                    long actualBytes,
                                                    ArchiveImportLimits limits,
                                                    String normalizedName) throws ArchiveImportLimitException {
        if (entry == null || limits == null) {
            return;
        }
        long compressedSize = entry.getCompressedSize();
        long declaredSize = entry.getSize();
        if (compressedSize > 0 && declaredSize > 0) {
            double ratio = (double) declaredSize / (double) compressedSize;
            if (ratio > limits.maxCompressionRatio) {
                throw new ArchiveImportLimitException(
                        ArchiveImportLimitException.Reason.COMPRESSION_RATIO,
                        normalizedName,
                        ratio,
                        limits.maxCompressionRatio
                );
            }
        }
        if (compressedSize > 0 && actualBytes > 0) {
            double ratio = (double) actualBytes / (double) compressedSize;
            if (ratio > limits.maxCompressionRatio) {
                throw new ArchiveImportLimitException(
                        ArchiveImportLimitException.Reason.COMPRESSION_RATIO,
                        normalizedName,
                        ratio,
                        limits.maxCompressionRatio
                );
            }
        }
    }

    private static void cleanupExtractionTree(Path root) {
        if (root == null) {
            return;
        }
        try {
            if (!Files.exists(root)) {
                return;
            }
            List<Path> paths = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                stream.forEach(paths::add);
            }
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    static record NormalizedEntry(String normalizedName, Path outputPath, boolean directory, String collisionKey) {
    }
}
