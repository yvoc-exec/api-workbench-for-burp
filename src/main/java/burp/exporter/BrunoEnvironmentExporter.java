package burp.exporter;

import burp.models.EnvironmentProfile;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Set;

public final class BrunoEnvironmentExporter {
    private BrunoEnvironmentExporter() {
    }

    public static void write(EnvironmentProfile profile, Writer writer, List<String> warnings) throws IOException {
        StringBuilder out = new StringBuilder("vars {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null) {
                    ExportWarningSupport.add(warnings, "Bruno environment export skipped a null variable key.");
                    continue;
                }
                String key = BrunoFormatSupport.uniqueRenderedKey(entry.getKey(), keys,
                        "environment '" + ExportWarningSupport.label(profile.displayName()) + "'", warnings);
                BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key,
                        entry.getValue() != null ? entry.getValue() : "",
                        "environment variable in '" + ExportWarningSupport.label(profile.displayName()) + "'", warnings);
            }
        }
        out.append("}\n");
        writer.write(out.toString());
    }
}
