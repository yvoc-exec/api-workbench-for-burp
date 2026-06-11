package burp.exporter;

import burp.models.EnvironmentProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EnvironmentExportService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public ExportResult exportEnvironment(EnvironmentProfile profile, EnvironmentExportOptions options) throws ExportException {
        if (profile == null) {
            throw new ExportException("Environment profile is required.");
        }
        if (options == null || options.format == null) {
            throw new ExportException("Environment export format is required.");
        }
        if (options.outputPath == null) {
            throw new ExportException("Environment export file path is required.");
        }

        List<String> warnings = new ArrayList<>();
        try {
            write(profile, options, warnings);
            int variableCount = profile.variables != null ? profile.variables.size() : 0;
            return new ExportResult(options.outputPath, options.format.displayName(), 0, variableCount, 0, warnings);
        } catch (IOException e) {
            throw new ExportException("Environment export failed: " + e.getMessage(), e);
        }
    }

    private void write(EnvironmentProfile profile, EnvironmentExportOptions options, List<String> warnings) throws IOException {
        Path output = ExportSupport.prepareOutputPath(options.outputPath);
        Files.createDirectories(output.getParent() != null ? output.getParent() : output.toAbsolutePath().getParent());
        switch (options.format) {
            case API_WORKBENCH_JSON -> writeText(output, GSON.toJson(ApiWorkbenchEnvironmentExporter.build(profile, warnings)));
            case POSTMAN_JSON -> writeText(output, GSON.toJson(PostmanEnvironmentExporter.build(profile, warnings)));
            case DOTENV -> {
                try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                    DotEnvEnvironmentExporter.write(profile, writer, warnings);
                }
            }
            case JSON_OBJECT -> writeText(output, GSON.toJson(GenericJsonEnvironmentExporter.build(profile, warnings)));
            case INSOMNIA_JSON -> writeText(output, GSON.toJson(InsomniaEnvironmentExporter.build(profile, warnings)));
            case BRUNO_BRU -> {
                try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                    BrunoEnvironmentExporter.write(profile, writer, warnings);
                }
            }
            default -> throw new IOException("Unsupported environment export format: " + options.format);
        }
    }

    private static void writeText(Path output, String text) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(text != null ? text : "");
        }
    }
}
