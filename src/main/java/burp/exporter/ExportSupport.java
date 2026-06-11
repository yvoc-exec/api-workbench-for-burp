package burp.exporter;

import java.nio.file.Path;

final class ExportSupport {
    private ExportSupport() {
    }

    static Path prepareOutputPath(Path outputPath) {
        if (outputPath == null) {
            return null;
        }
        return outputPath.toAbsolutePath().normalize();
    }
}
