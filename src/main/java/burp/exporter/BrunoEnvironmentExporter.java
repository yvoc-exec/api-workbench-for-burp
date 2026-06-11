package burp.exporter;

import burp.models.EnvironmentProfile;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

public final class BrunoEnvironmentExporter {
    private BrunoEnvironmentExporter() {
    }

    public static void write(EnvironmentProfile profile, Writer writer, List<String> warnings) throws IOException {
        writer.write("vars {\n");
        if (profile != null && profile.variables != null) {
            for (var entry : profile.variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    if (warnings != null) {
                        warnings.add("Skipped blank environment variable key.");
                    }
                    continue;
                }
                writer.write("  ");
                writer.write(escapeKey(entry.getKey()));
                writer.write(": ");
                writer.write(escapeValue(entry.getValue() != null ? entry.getValue() : ""));
                writer.write("\n");
            }
        }
        writer.write("}\n");
    }

    private static String escapeKey(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(":")
                || value.contains("#")
                || value.contains("\"")
                || value.contains("'")
                || value.contains("\\")
                || value.contains("{")
                || value.contains("}")
                || value.contains(" ")
                || value.contains("\t")
                || value.contains("\n")
                || value.contains("\r");
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        if (needsQuotes) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
