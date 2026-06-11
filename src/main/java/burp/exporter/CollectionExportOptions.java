package burp.exporter;

import burp.models.EnvironmentProfile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CollectionExportOptions {
    public final CollectionExportFormat format;
    public final Path outputPath;
    public final boolean resolveVariablesUsingActiveEnvironment;
    public final EnvironmentProfile activeEnvironment;
    public final Map<String, String> exportOnlyVariables;

    public CollectionExportOptions(CollectionExportFormat format,
                                   Path outputPath,
                                   boolean resolveVariablesUsingActiveEnvironment,
                                   EnvironmentProfile activeEnvironment,
                                   Map<String, String> exportOnlyVariables) {
        this.format = format;
        this.outputPath = outputPath;
        this.resolveVariablesUsingActiveEnvironment = resolveVariablesUsingActiveEnvironment;
        this.activeEnvironment = activeEnvironment;
        this.exportOnlyVariables = exportOnlyVariables != null
                ? new LinkedHashMap<>(exportOnlyVariables)
                : new LinkedHashMap<>();
    }
}
