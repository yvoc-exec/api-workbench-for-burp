package burp.fidelity;

import burp.exporter.CollectionExportFormat;
import burp.exporter.CollectionExportOptions;
import burp.exporter.CollectionExportService;
import burp.exporter.ExportResult;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.CollectionParser;
import burp.parser.ParserRegistry;
import burp.parser.VariableResolver;
import burp.scripts.capabilities.ScriptTrustReviewModel;
import burp.utils.RequestBuilder;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class Wave6FixtureSupport {
    private static final String RESOURCE_ROOT = "fidelity/wave6/";

    private Wave6FixtureSupport() {
    }

    enum FixtureCase {
        POSTMAN_V21("Postman v2.1", "postman/wave6-v21.postman_collection.json",
                List.of("postman/wave6-v21.postman_collection.json"),
                CollectionExportFormat.POSTMAN_JSON, "wave6.postman_collection.json"),
        POSTMAN_V20("Postman v2.0", "postman/wave6-v20.postman_collection.json",
                List.of("postman/wave6-v20.postman_collection.json"),
                CollectionExportFormat.POSTMAN_JSON, "wave6-v20-normalized.postman_collection.json"),
        BRUNO_FOLDER("Bruno folder", "bruno/Wave6",
                List.of("bruno/Wave6/bruno.json", "bruno/Wave6/collection.bru",
                        "bruno/Wave6/Core/Core.bru", "bruno/Wave6/Core/Nested/Nested.bru",
                        "bruno/Wave6/Core/Nested/Canonical_GET.bru",
                        "bruno/Wave6/Bodies/Structured_Body.bru"),
                CollectionExportFormat.BRUNO_ZIP, "wave6.bruno.zip"),
        INSOMNIA_V4("Insomnia", "insomnia/wave6.insomnia.json",
                List.of("insomnia/wave6.insomnia.json"),
                CollectionExportFormat.INSOMNIA_JSON, "wave6.insomnia.json"),
        OPENAPI_31_JSON("OpenAPI 3.1 JSON", "openapi/main.yaml",
                List.of("openapi/main.yaml", "openapi/components.yaml"),
                CollectionExportFormat.OPENAPI_JSON, "wave6.openapi.json"),
        OPENAPI_31_YAML("OpenAPI 3.1 YAML", "openapi/main.yaml",
                List.of("openapi/main.yaml", "openapi/components.yaml"),
                CollectionExportFormat.OPENAPI_YAML, "wave6.openapi.yaml"),
        SWAGGER_20_JSON("Swagger 2.0", "openapi/swagger20.yaml",
                List.of("openapi/swagger20.yaml"),
                CollectionExportFormat.OPENAPI_JSON, "wave6-swagger.openapi.json"),
        HAR_12("HAR 1.2", "har/wave6.har", List.of("har/wave6.har"),
                CollectionExportFormat.HAR_JSON, "wave6-roundtrip.har"),
        NATIVE_V2("API Workbench schema 2", "native/wave6.api-workbench.collection.json",
                List.of("native/wave6.api-workbench.collection.json"),
                CollectionExportFormat.API_WORKBENCH_JSON, "wave6.api-workbench.collection.json");

        final String displayName;
        final String sourceEntry;
        final List<String> resourceFiles;
        final CollectionExportFormat targetFormat;
        final String targetFileName;

        FixtureCase(String displayName,
                    String sourceEntry,
                    List<String> resourceFiles,
                    CollectionExportFormat targetFormat,
                    String targetFileName) {
            this.displayName = displayName;
            this.sourceEntry = sourceEntry;
            this.resourceFiles = resourceFiles;
            this.targetFormat = targetFormat;
            this.targetFileName = targetFileName;
        }

        boolean targetTransportIsExactlyRepresentable() {
            return this != INSOMNIA_V4 && this != BRUNO_FOLDER;
        }
    }

    record LifecycleResult(
            FixtureCase fixture,
            Path sourcePath,
            ApiCollection sourceImport,
            Path firstNativeFile,
            ExportResult firstNativeExport,
            ApiCollection nativeReload,
            Path secondNativeFile,
            ExportResult secondNativeExport,
            Path targetFile,
            ExportResult targetExport,
            ApiCollection targetReload,
            Map<String, byte[]> sourceBuiltRequests,
            Map<String, byte[]> nativeBuiltRequests,
            Map<String, byte[]> targetBuiltRequests) {
    }

    static LifecycleResult runLifecycle(FixtureCase fixture, Path tempDirectory) throws Exception {
        Path caseDirectory = tempDirectory.resolve(fixture.name().toLowerCase());
        for (String resource : fixture.resourceFiles) {
            copyResource(RESOURCE_ROOT + resource, caseDirectory.resolve(resource));
        }
        Path source = caseDirectory.resolve(fixture.sourceEntry);
        ApiCollection sourceImport = parse(source);

        CollectionExportService exporter = new CollectionExportService();
        Path firstNative = caseDirectory.resolve("first.api-workbench.collection.json");
        ExportResult firstNativeResult = exporter.exportCollection(sourceImport,
                options(CollectionExportFormat.API_WORKBENCH_JSON, firstNative));
        ApiCollection nativeReload = parse(firstNative);

        Path secondNative = caseDirectory.resolve("second.api-workbench.collection.json");
        ExportResult secondNativeResult = exporter.exportCollection(nativeReload,
                options(CollectionExportFormat.API_WORKBENCH_JSON, secondNative));
        JsonElement firstTree = JsonParser.parseString(Files.readString(firstNative));
        JsonElement secondTree = JsonParser.parseString(Files.readString(secondNative));
        assertThat(secondTree).as(fixture.displayName + " native JSON fixed point").isEqualTo(firstTree);

        Path target = caseDirectory.resolve(fixture.targetFileName);
        ExportResult targetResult = exporter.exportCollection(nativeReload,
                options(fixture.targetFormat, target));
        ApiCollection targetReload = parse(target);

        Map<String, byte[]> sourceBuilt = buildEnabledRequests(sourceImport);
        Map<String, byte[]> nativeBuilt = buildEnabledRequests(nativeReload);
        Map<String, byte[]> targetBuilt = buildEnabledRequests(targetReload);
        return new LifecycleResult(fixture, source, sourceImport, firstNative, firstNativeResult,
                nativeReload, secondNative, secondNativeResult, target, targetResult, targetReload,
                sourceBuilt, nativeBuilt, targetBuilt);
    }

    static Map<String, byte[]> buildEnabledRequests(ApiCollection collection) throws Exception {
        Map<String, byte[]> built = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("baseUrl", "https://example.test");
        overrides.put("id", "42");
        overrides.put("token", "wave6-token");
        overrides.put("scope", "nested");
        overrides.put("requestVar", "request");
        overrides.put("wave6", "one");
        if (collection == null || collection.requests == null) {
            return built;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null || request.disabled) {
                continue;
            }
            VariableResolver resolver = new VariableResolver();
            resolver.addCollectionVariables(collection);
            resolver.addFolderVariables(collection, request);
            resolver.addRequestVariables(request);
            resolver.addAll(overrides);
            String folder = RequestPathResolver.getRequestFolderPath(collection, request);
            String key = (folder == null || folder.isBlank() ? "<root>" : folder)
                    + "::" + request.name;
            byte[] previous = built.put(key, new RequestBuilder(null).buildRequest(request, resolver));
            if (previous != null) {
                throw new AssertionError("Duplicate request key: " + key);
            }
        }
        return built;
    }

    static ApiRequest requestByName(ApiCollection collection, String name) {
        return collection.requests.stream()
                .filter(request -> request != null && name.equals(request.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Request not found: " + name));
    }

    static String rawText(Map<String, byte[]> requests, String key) {
        byte[] raw = requests.get(key);
        if (raw == null) {
            throw new AssertionError("Built request not found: " + key + "; keys=" + requests.keySet());
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    static void assertSameBuiltRequests(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((key, value) -> assertThat(actual.get(key)).as(key).isEqualTo(value));
    }

    static Path copyResource(String classpathResource, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = Wave6FixtureSupport.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + classpathResource);
            }
            Files.copy(input, destination);
        }
        return destination;
    }

    static String readUtf8Resource(String classpathResource) throws IOException {
        try (InputStream input = Wave6FixtureSupport.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + classpathResource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ApiCollection parse(Path source) throws Exception {
        ParserRegistry.setScriptTrustReviewHandler(
                model -> ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED);
        try {
            CollectionParser parser = new ParserRegistry().detectParser(source.toFile());
            if (parser == null) {
                throw new AssertionError("No parser detected for " + source);
            }
            return parser.parse(source.toFile());
        } finally {
            ParserRegistry.clearScriptTrustReviewHandler();
        }
    }

    private static CollectionExportOptions options(CollectionExportFormat format, Path output) {
        return new CollectionExportOptions(format, output, false, null, Map.of());
    }
}
