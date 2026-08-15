package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.payload.ManagedPayloadLease;
import burp.payload.ManagedPayloadReader;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
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
    private final ManagedPayloadReader payloadReader;

    public CollectionExportService() {
        this(null);
    }

    public CollectionExportService(ManagedPayloadReader payloadReader) {
        this.payloadReader = payloadReader;
    }

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
        try {
            ApiCollection exportCollection = materializeExactPayloads(collection);
            CollectionExportSupport.addScriptExportWarnings(exportCollection, options.format, warnings);
            Path output = ExportSupport.prepareOutputPath(options.outputPath);
            ExportSupport.writeAtomically(output, temp -> {
                switch (options.format) {
                    case API_WORKBENCH_JSON -> writeText(temp, GSON.toJson(ApiWorkbenchCollectionExporter.build(exportCollection, options, warnings)));
                    case POSTMAN_JSON -> writeText(temp, GSON.toJson(PostmanCollectionExporter.build(exportCollection, options, warnings)));
                    case OPENAPI_JSON -> {
                        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                            OpenApiCollectionExporter.writeJson(exportCollection, options, writer, warnings);
                        }
                    }
                    case OPENAPI_YAML -> {
                        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                            OpenApiCollectionExporter.writeYaml(exportCollection, options, writer, warnings);
                        }
                    }
                    case INSOMNIA_JSON -> writeText(temp, GSON.toJson(InsomniaCollectionExporter.build(exportCollection, options, warnings)));
                    case HAR_JSON -> writeText(temp, GSON.toJson(HarCollectionExporter.build(exportCollection, options, warnings)));
                    case BRUNO_ZIP -> {
                        try (OutputStream out = Files.newOutputStream(temp)) {
                            BrunoCollectionExporter.write(exportCollection, options, out, warnings);
                        }
                    }
                    default -> throw new IOException("Unsupported collection export format: " + options.format);
                }
            });
            int unresolved = 0;
            if (options.resolveVariablesUsingActiveEnvironment) {
                unresolved = options.format == CollectionExportFormat.BRUNO_ZIP
                        || options.format == CollectionExportFormat.INSOMNIA_JSON
                        ? ExportVariableResolutionService.collectUnresolvedIssuesFromArtifact(
                        output, options.format, collection).size()
                        : ExportVariableResolutionService.collectUnresolvedIssues(
                        collection, options.activeEnvironment, options.exportOnlyVariables).size();
            }
            int requestCount = collection.requests != null ? collection.requests.size() : 0;
            return new ExportResult(output, options.format.displayName(), requestCount, 0, unresolved, warnings);
        } catch (IOException e) {
            throw new ExportException("Collection export failed: " + e.getMessage(), e);
        }
    }

    private ApiCollection materializeExactPayloads(ApiCollection source) throws IOException {
        WorkspaceState wrapper = new WorkspaceState();
        wrapper.collections = List.of(source);
        WorkspaceState detached = WorkspaceState.copyOfSharingPersistencePayload(wrapper);
        ApiCollection copy = detached.collections.get(0);
        for (ApiRequest request : copy.requests != null ? copy.requests : List.<ApiRequest>of()) {
            if (request == null || request.exactHttpRequest == null
                    || !request.exactHttpRequest.hasManagedPayload()) continue;
            if (payloadReader == null) {
                throw new IOException("Exact payload is unavailable for export.");
            }
            if (request.exactHttpRequest.pristine) {
                try (ManagedPayloadLease lease = payloadReader.materialize(request.exactHttpRequest.payloadRef)) {
                    request.exactHttpRequest.rawRequestBytes = lease.bytes();
                }
            } else {
                try {
                    request.exactHttpRequest.rawRequestBytes =
                            new RequestBuilder(null, payloadReader).buildRequest(request, new VariableResolver());
                    request.exactHttpRequest.pristine = true;
                } catch (Exception failure) {
                    throw new IOException("Managed exact payload could not be materialized for export.", failure);
                }
            }
        }
        return copy;
    }

    private static void writeText(Path output, String text) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(text != null ? text : "");
        }
    }
}
