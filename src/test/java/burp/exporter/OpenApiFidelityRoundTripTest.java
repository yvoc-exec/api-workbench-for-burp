package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.ApiWorkbenchCollectionParser;
import burp.parser.OpenApiParser;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiFidelityRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void openApiNativeAndOpenApiRoundTripRetainsUnifiedParametersBodiesAndMetadata() throws Exception {
        Path source = tempDir.resolve("source.yaml");
        Files.writeString(source, """
                openapi: 3.1.0
                info: {title: T, version: '1'}
                x-root: retained
                paths:
                  /users/{id}:
                    post:
                      x-op: retained
                      parameters:
                        - {name: id, in: path, required: true, style: matrix, explode: false, schema: {type: string, default: '7'}}
                        - {name: q, in: query, x-disabled: true, schema: {type: array, items: {type: string}, example: [a,b]}}
                        - {name: X-T, in: header, schema: {type: string, default: h}}
                        - {name: sid, in: cookie, schema: {type: string, default: c}}
                      requestBody:
                        required: true
                        content:
                          application/json: {schema: {type: object, properties: {name: {type: string, default: n}}}}
                          text/plain: {example: alternative}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection imported = new OpenApiParser().parse(source.toFile());

        Path nativeFile = tempDir.resolve("native.json");
        Files.writeString(nativeFile, new GsonBuilder().setPrettyPrinting().create().toJson(
                ApiWorkbenchCollectionExporter.build(imported, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        assertEquivalent(imported.requests.get(0), nativeRoundTrip.requests.get(0));

        Path openApiFile = tempDir.resolve("export.json");
        Files.writeString(openApiFile, new GsonBuilder().setPrettyPrinting().create().toJson(
                OpenApiCollectionExporter.build(nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>())));
        ApiCollection reimported = new OpenApiParser().parse(openApiFile.toFile());
        assertEquivalent(nativeRoundTrip.requests.get(0), reimported.requests.get(0));
        assertThat(reimported.sourceMetadata).containsKey("openapi.document.extensions");
    }

    @Test
    void fileBodyExportOmitsLocalPathAndEmitsBinarySchema() {
        ApiCollection collection = new ApiCollection();
        collection.name = "T";
        ApiRequest request = new ApiRequest();
        request.name = "upload"; request.method = "POST"; request.url = "https://example.test/x";
        request.body = new ApiRequest.Body();
        request.body.mode = "file"; request.body.filePath = "C:/secret/file.bin"; request.body.contentType = "application/octet-stream";
        collection.requests.add(request);
        String json = new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                collection, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>()));
        assertThat(json).doesNotContain("C:/secret").contains("binary");
    }

    @Test
    void builtRequestRemainsEquivalentAfterOpenApiExportAndReimport() throws Exception {
        Path source = tempDir.resolve("built-source.yaml");
        Files.writeString(source, """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                servers: [{url: 'https://example.test'}]
                paths:
                  /x/{id}:
                    post:
                      parameters:
                        - {name: id, in: path, required: true, style: simple, schema: {type: string, default: '7'}}
                        - {name: q, in: query, style: form, explode: true, schema: {type: array, example: [a,b], items: {type: string}}}
                        - {name: X-T, in: header, schema: {type: string, default: h}}
                        - {name: sid, in: cookie, schema: {type: string, default: c}}
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                            schema:
                              type: object
                              properties:
                                tags: {type: array, example: [one,two], items: {type: string}}
                            encoding: {tags: {style: form, explode: true}}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection before = new OpenApiParser().parse(source.toFile());
        Path exported = tempDir.resolve("built-export.json");
        Files.writeString(exported, new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                before, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>())));
        ApiCollection after = new OpenApiParser().parse(exported.toFile());
        RequestBuilder builder = new RequestBuilder(null);
        byte[] left = builder.buildRequest(before.requests.get(0), new VariableResolver());
        byte[] right = builder.buildRequest(after.requests.get(0), new VariableResolver());
        assertThat(right).isEqualTo(left);
    }

    private static void assertEquivalent(ApiRequest left, ApiRequest right) {
        assertThat(right.method).isEqualTo(left.method);
        assertThat(right.url).isEqualTo(left.url);
        assertThat(right.parameters).extracting(p -> p.location + ":" + p.key + ":" + p.disabled)
                .containsExactlyElementsOf(left.parameters.stream().map(p -> p.location + ":" + p.key + ":" + p.disabled).toList());
        assertThat(right.body.mode).isEqualTo(left.body.mode);
        assertThat(right.body.contentType).isEqualTo(left.body.contentType);
        assertThat(right.body.required).isEqualTo(left.body.required);
    }

    private static CollectionExportOptions options(CollectionExportFormat format) {
        return new CollectionExportOptions(format, null, false, null, Map.of());
    }
}
