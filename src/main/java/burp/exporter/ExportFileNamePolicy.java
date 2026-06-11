package burp.exporter;

import java.nio.file.Path;

public final class ExportFileNamePolicy {
    private ExportFileNamePolicy() {
    }

    public static String sanitizeBaseName(String value) {
        if (value == null || value.isBlank()) {
            return "export";
        }
        String sanitized = value.trim()
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("[\\x00-\\x1F\\x7F]+", "_")
                .replaceAll("[<>:\"|?*]+", "_")
                .replaceAll("\\s+", " ");
        sanitized = sanitized.trim();
        if (sanitized.isBlank()) {
            return "export";
        }
        return sanitized;
    }

    public static Path ensureExtension(Path file, String defaultExtension) {
        if (file == null || defaultExtension == null || defaultExtension.isBlank()) {
            return file;
        }
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        if (name.toLowerCase().endsWith(defaultExtension.toLowerCase())) {
            return file;
        }
        String base = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && !defaultExtension.equalsIgnoreCase(".env")) {
            base = name.substring(0, dot);
        }
        Path parent = file.getParent();
        Path resolved = (parent != null ? parent : Path.of("")).resolve(base + defaultExtension);
        return resolved.normalize();
    }

    public static String defaultFileName(String baseName, String extension) {
        return sanitizeBaseName(baseName) + (extension != null ? extension : "");
    }
}
