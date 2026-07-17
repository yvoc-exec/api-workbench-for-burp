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
        assertThat(nativeRoundTrip.sourceMetadata).isEqualTo(imported.sourceMetadata);
        assertThat(nativeRoundTrip.requests.get(0).sourceMetadata).isEqualTo(imported.requests.get(0).sourceMetadata);
        assertNativeMetadata(imported.requests.get(0), nativeRoundTrip.requests.get(0));
        assertEquivalent(imported.requests.get(0), nativeRoundTrip.requests.get(0));

        Path openApiFile = tempDir.resolve("export.json");
        Files.writeString(openApiFile, new GsonBuilder().setPrettyPrinting().create().toJson(
                OpenApiCollectionExporter.build(nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>())));
        ApiCollection reimported = new OpenApiParser().parse(openApiFile.toFile());
        assertEquivalent(nativeRoundTrip.requests.get(0), reimported.requests.get(0), false);
        assertThat(reimported.sourceMetadata).containsKey("openapi.document.extensions");
    }

    @Test
    void swaggerTwoNativeAndOpenApiRoundTripPreservesTransportModel() throws Exception {
        Path source = tempDir.resolve("swagger.yaml");
        Files.writeString(source, """
                swagger: '2.0'
                info: {title: Swagger, version: '2'}
                host: swagger.example.test
                basePath: /v2
                schemes: [https]
                consumes: [application/x-www-form-urlencoded]
                produces: [application/json]
                paths:
                  /items/{id}:
                    post:
                      parameters:
                        - {name: id, in: path, required: true, type: string, format: uuid, x-example: abc}
                        - {name: q, in: query, type: array, items: {type: string}, collectionFormat: multi, x-example: [a,b]}
                        - {name: name, in: formData, type: string, required: true, default: item}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection imported = new OpenApiParser().parse(source.toFile());
        Path nativeFile = tempDir.resolve("swagger-native.json");
        Files.writeString(nativeFile, new GsonBuilder().create().toJson(ApiWorkbenchCollectionExporter.build(
                imported, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        assertEquivalent(imported.requests.get(0), nativeRoundTrip.requests.get(0));
        assertNativeMetadata(imported.requests.get(0), nativeRoundTrip.requests.get(0));

        Path exported = tempDir.resolve("swagger-export.json");
        Files.writeString(exported, new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>())));
        ApiCollection reimported = new OpenApiParser().parse(exported.toFile());
        assertEquivalent(nativeRoundTrip.requests.get(0), reimported.requests.get(0), false);
        assertThat(new RequestBuilder(null).buildRequest(reimported.requests.get(0), new VariableResolver()))
                .isEqualTo(new RequestBuilder(null).buildRequest(nativeRoundTrip.requests.get(0), new VariableResolver()));
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

    @Test
    void referencedFormSchemasExportPortablyWithoutDanglingReferences() throws Exception {
        Files.writeString(tempDir.resolve("defs.yaml"), """
                Scalar: {type: string, format: uuid, default: abc}
                Item:
                  type: object
                  properties: {code: {$ref: '#/Scalar'}}
                Bag:
                  type: object
                  properties:
                    item: {$ref: '#/Item'}
                    values: {type: array, items: {$ref: '#/Scalar'}}
                """);
        Path source = tempDir.resolve("portable.yaml");
        Files.writeString(source, """
                openapi: 3.0.3
                info: {title: Portable, version: '1'}
                servers: [{url: https://example.test}]
                components:
                  schemas:
                    Binary: {type: string, format: binary}
                paths:
                  /form:
                    post:
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                            schema: {$ref: 'defs.yaml#/Bag'}
                          text/plain:
                            schema: {$ref: 'defs.yaml#/Item'}
                      responses: {'200': {description: ok}}
                  /upload:
                    post:
                      requestBody:
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                file: {$ref: '#/components/schemas/Binary'}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection imported = new OpenApiParser().parse(source.toFile());
        Path nativeFile = tempDir.resolve("portable-native.json");
        Files.writeString(nativeFile, new GsonBuilder().create().toJson(ApiWorkbenchCollectionExporter.build(
                imported, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());

        ArrayList<String> warnings = new ArrayList<>();
        String exportedJson = new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), warnings));
        assertThat(exportedJson).doesNotContain("\"$ref\"");
        Path exported = tempDir.resolve("portable-export.json");
        Files.writeString(exported, exportedJson);
        ApiCollection reimported = new OpenApiParser().parse(exported.toFile());

        ApiRequest formBefore = nativeRoundTrip.requests.get(0);
        ApiRequest formAfter = reimported.requests.get(0);
        assertThat(formAfter.body.urlencoded).extracting(f -> f.key).containsExactly("item", "values");
        assertThat(formAfter.body.urlencoded).extracting(f -> f.type).containsExactly("object", "array");
        assertThat(formAfter.body.urlencoded).extracting(f -> f.value)
                .containsExactly("{\"code\":\"abc\"}", "[\"abc\"]");
        assertThat(new RequestBuilder(null).buildRequest(formAfter, new VariableResolver()))
                .isEqualTo(new RequestBuilder(null).buildRequest(formBefore, new VariableResolver()));
        assertThat(reimported.requests.get(1).body.formdata.get(0).fileUpload).isTrue();
        assertThat(reimported.requests.get(1).body.formdata.get(0).sourceMetadata)
                .containsKey("openapi.resolvedSchema");
    }

    @Test
    void serverPrecedenceAndScopedTemplatesSurviveExportAndReimport() throws Exception {
        Path source = tempDir.resolve("servers.yaml");
        Files.writeString(source, """
                openapi: 3.0.3
                info: {title: Servers, version: '1'}
                servers:
                  - url: https://{root}.example.test/api
                    variables: {root: {default: root}}
                paths:
                  /items:
                    servers:
                      - url: http://{tenant}.example.test:8080/base
                        variables: {tenant: {default: path}}
                    get: {operationId: pathServer, responses: {'200': {description: ok}}}
                    post:
                      operationId: operationServer
                      servers:
                        - url: https://{tenant}.example.test:9443/op
                          variables: {tenant: {default: operation}}
                      responses: {'200': {description: ok}}
                  /root:
                    get: {operationId: rootServer, responses: {'200': {description: ok}}}
                """);
        ApiCollection before = new OpenApiParser().parse(source.toFile());
        Path exported = tempDir.resolve("servers-export.json");
        Files.writeString(exported, new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                before, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>())));
        ApiCollection after = new OpenApiParser().parse(exported.toFile());

        assertThat(after.requests).extracting(r -> r.url).containsExactly(
                "http://{{tenant}}.example.test:8080/base/items",
                "https://{{tenant}}.example.test:9443/op/items",
                "https://{{root}}.example.test/api/root");
        for (int i = 0; i < before.requests.size(); i++) {
            assertThat(buildWithCollectionVariables(after, after.requests.get(i)))
                    .isEqualTo(buildWithCollectionVariables(before, before.requests.get(i)));
        }
    }

    private static byte[] buildWithCollectionVariables(ApiCollection collection, ApiRequest request) throws Exception {
        VariableResolver resolver = new VariableResolver();
        resolver.addAll(collection.environment);
        resolver.addRequestVariables(request);
        return new RequestBuilder(null).buildRequest(request, resolver);
    }

    private static void assertEquivalent(ApiRequest left, ApiRequest right) {
        assertEquivalent(left, right, true);
    }

    private static void assertEquivalent(ApiRequest left, ApiRequest right, boolean compareSource) {
        assertThat(right.method).isEqualTo(left.method);
        assertThat(right.url).isEqualTo(left.url);
        assertThat(right.parameters).hasSameSizeAs(left.parameters);
        for (int i = 0; i < left.parameters.size(); i++) {
            ApiRequest.Parameter expected = left.parameters.get(i);
            ApiRequest.Parameter actual = right.parameters.get(i);
            assertThat(actual.location).isEqualTo(expected.location);
            assertThat(actual.key).isEqualTo(expected.key);
            assertThat(actual.value).isEqualTo(expected.value);
            assertThat(actual.valuePresent).isEqualTo(expected.valuePresent);
            assertThat(actual.disabled).isEqualTo(expected.disabled);
            assertThat(actual.required).isEqualTo(expected.required);
            assertThat(actual.type).isEqualTo(expected.type);
            assertThat(actual.format).isEqualTo(expected.format);
            assertThat(actual.description).isEqualTo(expected.description);
            assertThat(actual.style).isEqualTo(expected.style);
            assertThat(actual.explode).isEqualTo(expected.explode);
            assertThat(actual.allowReserved).isEqualTo(expected.allowReserved);
            if (compareSource) assertThat(actual.source).isEqualTo(expected.source);
        }
        assertThat(right.body.mode).isEqualTo(left.body.mode);
        assertThat(right.body.contentType).isEqualTo(left.body.contentType);
        assertThat(right.body.required).isEqualTo(left.body.required);
        assertThat(right.body.description).isEqualTo(left.body.description);
        if (compareSource) assertThat(right.body.source).isEqualTo(left.body.source);
        assertFields(left.body.urlencoded, right.body.urlencoded, compareSource);
        assertFields(left.body.formdata, right.body.formdata, compareSource);
    }

    private static void assertFields(java.util.List<ApiRequest.Body.FormField> left,
                                     java.util.List<ApiRequest.Body.FormField> right,
                                     boolean compareSource) {
        assertThat(right).hasSameSizeAs(left);
        for (int i = 0; i < left.size(); i++) {
            ApiRequest.Body.FormField expected = left.get(i);
            ApiRequest.Body.FormField actual = right.get(i);
            assertThat(actual.key).isEqualTo(expected.key);
            assertThat(actual.value).isEqualTo(expected.value);
            assertThat(actual.type).isEqualTo(expected.type);
            assertThat(actual.required).isEqualTo(expected.required);
            assertThat(actual.disabled).isEqualTo(expected.disabled);
            assertThat(actual.fileUpload).isEqualTo(expected.fileUpload);
            assertThat(actual.filePath).isEqualTo(expected.filePath);
            assertThat(actual.contentType).isEqualTo(expected.contentType);
            assertThat(actual.style).isEqualTo(expected.style);
            assertThat(actual.explode).isEqualTo(expected.explode);
            assertThat(actual.allowReserved).isEqualTo(expected.allowReserved);
            if (compareSource) assertThat(actual.source).isEqualTo(expected.source);
        }
    }

    private static void assertNativeMetadata(ApiRequest left, ApiRequest right) {
        assertThat(right.sourceMetadata).isEqualTo(left.sourceMetadata);
        for (int i = 0; i < left.parameters.size(); i++) {
            assertThat(right.parameters.get(i).sourceMetadata).isEqualTo(left.parameters.get(i).sourceMetadata);
        }
        assertThat(right.body.sourceMetadata).isEqualTo(left.body.sourceMetadata);
        for (int i = 0; i < left.body.urlencoded.size(); i++) {
            assertThat(right.body.urlencoded.get(i).sourceMetadata).isEqualTo(left.body.urlencoded.get(i).sourceMetadata);
        }
        for (int i = 0; i < left.body.formdata.size(); i++) {
            assertThat(right.body.formdata.get(i).sourceMetadata).isEqualTo(left.body.formdata.get(i).sourceMetadata);
        }
    }

    private static CollectionExportOptions options(CollectionExportFormat format) {
        return new CollectionExportOptions(format, null, false, null, Map.of());
    }
}
