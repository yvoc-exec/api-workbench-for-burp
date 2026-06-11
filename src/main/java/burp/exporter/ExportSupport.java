package burp.exporter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ExportSupport {
    private ExportSupport() {
    }

    static Path prepareOutputPath(Path outputPath) {
        if (outputPath == null) {
            return null;
        }
        return outputPath.toAbsolutePath().normalize();
    }

    @FunctionalInterface
    interface TempFileWriter {
        void write(Path tempPath) throws IOException;
    }

    static void writeAtomically(Path outputPath, TempFileWriter writer) throws IOException {
        Path output = prepareOutputPath(outputPath);
        if (output == null) {
            throw new IOException("Output path is required.");
        }
        Path parent = output.getParent() != null ? output.getParent() : output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent != null ? parent : output.toAbsolutePath().getParent(), tempPrefix(output), ".tmp");
        try {
            writer.write(temp);
            try {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static String tempPrefix(Path output) {
        String name = output != null && output.getFileName() != null ? output.getFileName().toString() : "export";
        String sanitized = name.replaceAll("[^a-zA-Z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (sanitized.length() < 3) {
            sanitized = "exp";
        }
        if (sanitized.length() > 24) {
            sanitized = sanitized.substring(0, 24);
        }
        return sanitized;
    }
}
