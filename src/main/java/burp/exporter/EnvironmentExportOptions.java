package burp.exporter;

import java.nio.file.Path;

public final class EnvironmentExportOptions {
    public final EnvironmentExportFormat format;
    public final Path outputPath;

    public EnvironmentExportOptions(EnvironmentExportFormat format, Path outputPath) {
        this.format = format;
        this.outputPath = outputPath;
    }
}
