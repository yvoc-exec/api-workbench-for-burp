package burp.exporter;

import burp.models.EnvironmentProfile;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

public final class DotEnvEnvironmentExporter {
    private DotEnvEnvironmentExporter() {
    }

    public static void write(EnvironmentProfile profile, Writer writer, List<String> warnings) throws IOException {
        if (profile == null || profile.variables == null || profile.variables.isEmpty()) {
            return;
        }
        for (var entry : profile.variables.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                if (warnings != null) {
                    warnings.add("Skipped blank environment variable key.");
                }
                continue;
            }
            writer.write(key);
            writer.write('=');
            writer.write(escapeValue(entry.getValue() != null ? entry.getValue() : ""));
            writer.write(System.lineSeparator());
        }
    }

    static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.isEmpty()
                || value.startsWith(" ")
                || value.endsWith(" ")
                || value.contains(" ")
                || value.contains("\t")
                || value.contains("#")
                || value.contains("=")
                || value.contains("\"")
                || value.contains("\\")
                || value.contains("\n")
                || value.contains("\r");
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (needsQuotes) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
