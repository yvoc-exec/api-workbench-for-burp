package burp.exporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExportResult {
    public final Path outputPath;
    public final String formatLabel;
    public final int requestCount;
    public final int variableCount;
    public final int unresolvedVariableCount;
    public final List<String> warnings;

    public ExportResult(Path outputPath,
                        String formatLabel,
                        int requestCount,
                        int variableCount,
                        int unresolvedVariableCount,
                        List<String> warnings) {
        this.outputPath = outputPath;
        this.formatLabel = formatLabel;
        this.requestCount = requestCount;
        this.variableCount = variableCount;
        this.unresolvedVariableCount = unresolvedVariableCount;
        this.warnings = warnings == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(warnings));
    }
}
