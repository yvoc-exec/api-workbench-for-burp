package burp.exporter;

import burp.models.ApiCollection;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CollectionExportService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public ExportResult exportCollection(ApiCollection collection, CollectionExportOptions options) throws ExportException {
        if (collection == null) {
            throw new ExportException("Collection is required.");
        }
        if (options == null || options.format == null) {
            throw new ExportException("Collection export format is required.");
        }
        if (options.outputPath == null) {
            throw new ExportException("Collection export file path is required.");
        }

        List<String> warnings = new ArrayList<>();
        CollectionExportSupport.addScriptExportWarnings(collection, options.format, warnings);
        try {
            Path output = ExportSupport.prepareOutputPath(options.outputPath);
            ExportSupport.writeAtomically(output, temp -> {
                switch (options.format) {
                    case API_WORKBENCH_JSON -> writeText(temp, GSON.toJson(ApiWorkbenchCollectionExporter.build(collection, options, warnings)));
                    case POSTMAN_JSON -> writeText(temp, GSON.toJson(PostmanCollectionExporter.build(collection, options, warnings)));
                    case OPENAPI_JSON -> {
                        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                            OpenApiCollectionExporter.writeJson(collection, options, writer, warnings);
                        }
                    }
                    case OPENAPI_YAML -> {
                        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                            OpenApiCollectionExporter.writeYaml(collection, options, writer, warnings);
                        }
                    }
                    case INSOMNIA_JSON -> writeText(temp, GSON.toJson(InsomniaCollectionExporter.build(collection, options, warnings)));
                    case HAR_JSON -> writeText(temp, GSON.toJson(HarCollectionExporter.build(collection, options, warnings)));
                    case BRUNO_ZIP -> {
                        try (OutputStream out = Files.newOutputStream(temp)) {
                            BrunoCollectionExporter.write(collection, options, out, warnings);
                        }
                    }
                    default -> throw new IOException("Unsupported collection export format: " + options.format);
                }
            });
            int unresolved = options.resolveVariablesUsingActiveEnvironment
                    ? ExportVariableResolutionService.collectUnresolvedIssues(collection, options.activeEnvironment, options.exportOnlyVariables).size()
                    : 0;
            int requestCount = collection.requests != null ? collection.requests.size() : 0;
            return new ExportResult(output, options.format.displayName(), requestCount, 0, unresolved, warnings);
        } catch (IOException e) {
            throw new ExportException("Collection export failed: " + e.getMessage(), e);
        }
    }

    private static void writeText(Path output, String text) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(text != null ? text : "");
        }
    }
}
