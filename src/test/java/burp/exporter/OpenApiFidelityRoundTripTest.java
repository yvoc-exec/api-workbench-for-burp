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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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
        Map<String, Object> exportedArtifact = OpenApiCollectionExporter.build(
                nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), warnings);
        assertThat(collectRefs(exportedArtifact)).allMatch(ref -> ref.startsWith("#"));
        assertInternalRefsResolve(exportedArtifact);
        String exportedJson = new GsonBuilder().create().toJson(exportedArtifact);
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

    @Test
    void apiInfoVersionAndFeatureDialectRemainDistinctAndConformant() throws Exception {
        Path oas31 = tempDir.resolve("version-31.yaml");
        Files.writeString(oas31, """
                openapi: 3.1.0
                info: {title: V31, version: 2026.7}
                paths:
                  /x:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: [object, 'null']
                              properties:
                                encoded: {type: string, contentEncoding: base64}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection imported31 = new OpenApiParser().parse(oas31.toFile());
        Map<String, Object> exported31 = OpenApiCollectionExporter.build(
                imported31, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>());
        assertThat(imported31.version).isEqualTo("3.1.0");
        assertThat(imported31.sourceMetadata.get("openapi.sourceVersion")).isEqualTo("3.1.0");
        assertThat(exported31.get("openapi")).isEqualTo("3.1.0");
        assertThat(((Map<?, ?>) exported31.get("info")).get("version")).isEqualTo("2026.7");
        assertThat(new GsonBuilder().create().toJson(exported31))
                .contains("\"type\":[\"object\",\"null\"]", "\"contentEncoding\":\"base64\"");
        assertDialectConformant(exported31);

        Path swagger = tempDir.resolve("version-swagger.yaml");
        Files.writeString(swagger, """
                swagger: '2.0'
                info: {title: V2, version: v9}
                paths: {/x: {get: {responses: {'200': {description: ok}}}}}
                """);
        ApiCollection imported2 = new OpenApiParser().parse(swagger.toFile());
        Map<String, Object> exported2 = OpenApiCollectionExporter.build(
                imported2, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>());
        assertThat(imported2.version).isEqualTo("2.0");
        assertThat(imported2.sourceMetadata.get("openapi.sourceVersion")).isEqualTo("2.0");
        assertThat(exported2.get("openapi")).isEqualTo("3.0.3");
        assertThat(((Map<?, ?>) exported2.get("info")).get("version")).isEqualTo("v9");
        assertDialectConformant(exported2);
    }

    @Test
    void preservesValidInternalReferencesAndRemovesBlockedOrDanglingOnes() throws Exception {
        Path source = tempDir.resolve("references.yaml");
        Files.writeString(source, """
                openapi: 3.1.0
                info: {title: Refs, version: '1'}
                components:
                  schemas:
                    Node:
                      type: object
                      properties: {child: {$ref: '#/components/schemas/Node'}}
                    Always: true
                    Never: false
                    Pair:
                      type: object
                      properties:
                        a: {$ref: '#/components/schemas/Always'}
                        b: {$ref: '#/components/schemas/Always'}
                        f1: {$ref: '#/components/schemas/Never'}
                        f2: {$ref: '#/components/schemas/Never'}
                    Blocked: {$ref: 'https://blocked.example/schema.yaml'}
                    Missing: {$ref: '#/components/schemas/Absent'}
                  responses:
                    Common: {description: retained}
                  pathItems:
                    CommonPath: {get: {responses: {'200': {$ref: '#/components/responses/Common'}}}}
                paths:
                  /nodes:
                    post:
                      requestBody:
                        content: {application/json: {schema: {$ref: '#/components/schemas/Node'}}}
                      callbacks:
                        update: {$ref: '#/components/pathItems/CommonPath'}
                      responses: {'200': {$ref: '#/components/responses/Common'}}
                """);
        ApiCollection imported = new OpenApiParser().parse(source.toFile());
        ArrayList<String> warnings = new ArrayList<>();
        Map<String, Object> exported = OpenApiCollectionExporter.build(
                imported, options(CollectionExportFormat.OPENAPI_JSON), warnings);

        assertThat(collectRefs(exported)).contains(
                "#/components/schemas/Node",
                "#/components/schemas/Always",
                "#/components/schemas/Never",
                "#/components/responses/Common",
                "#/components/pathItems/CommonPath");
        assertThat(collectRefs(exported)).allMatch(ref -> ref.startsWith("#"));
        assertInternalRefsResolve(exported);
        assertThat(warnings).anyMatch(w -> w.contains("blocked or dangling reference"));
        assertThat(warnings).allMatch(w -> !w.contains("blocked.example") && !w.contains("Absent"));
        assertDialectConformant(exported);
    }

    @Test
    void exportedReferenceSiblingsFollowTheSelectedDialect() throws Exception {
        Path source30 = tempDir.resolve("export-siblings-30.yaml");
        Files.writeString(source30, """
                openapi: 3.0.3
                info: {title: S30, version: '1'}
                components:
                  schemas:
                    Base: {type: string}
                    Alias: {$ref: '#/components/schemas/Base', type: integer, description: ignored}
                  responses:
                    Common: {description: ok}
                paths:
                  /x:
                    get:
                      responses:
                        '200': {$ref: '#/components/responses/Common', description: ignored}
                """);
        Map<String, Object> exported30 = OpenApiCollectionExporter.build(
                new OpenApiParser().parse(source30.toFile()),
                options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>());
        Map<String, Object> components30 = cast(exported30.get("components"));
        Map<String, Object> alias30 = cast(cast(components30.get("schemas")).get("Alias"));
        assertThat(alias30).containsOnly(entry("$ref", "#/components/schemas/Base"));
        Map<String, Object> response30 = cast(cast(cast(cast(exported30.get("paths")).get("/x")).get("get"))
                .get("responses"));
        assertThat(cast(response30.get("200"))).containsOnly(entry("$ref", "#/components/responses/Common"));
        assertInternalRefsResolve(exported30);

        Path source31 = tempDir.resolve("export-siblings-31.yaml");
        Files.writeString(source31, """
                openapi: 3.1.0
                info: {title: S31, version: '1'}
                components:
                  schemas:
                    Base: {type: string}
                    Alias: {$ref: '#/components/schemas/Base', const: fixed, description: allowed}
                  responses:
                    Common: {description: ok}
                paths:
                  /x:
                    get:
                      responses:
                        '200':
                          $ref: '#/components/responses/Common'
                          summary: allowed
                          description: allowed
                          content: {application/json: {schema: {type: string}}}
                """);
        Map<String, Object> exported31 = OpenApiCollectionExporter.build(
                new OpenApiParser().parse(source31.toFile()),
                options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>());
        Map<String, Object> components31 = cast(exported31.get("components"));
        assertThat(cast(cast(components31.get("schemas")).get("Alias")))
                .containsEntry("$ref", "#/components/schemas/Base")
                .containsEntry("const", "fixed")
                .containsEntry("description", "allowed");
        Map<String, Object> response31 = cast(cast(cast(cast(exported31.get("paths")).get("/x")).get("get"))
                .get("responses"));
        assertThat(cast(response31.get("200")))
                .containsEntry("$ref", "#/components/responses/Common")
                .containsEntry("summary", "allowed")
                .containsEntry("description", "allowed")
                .doesNotContainKey("content");
        assertInternalRefsResolve(exported31);
    }

    @Test
    void pathItemReferencesRetainLocalSiblingsAcrossAllSupportedContexts() throws Exception {
        Path source30 = tempDir.resolve("path-item-30.yaml");
        Files.writeString(source30, """
                openapi: 3.0.3
                info: {title: PathItems, version: '1'}
                components:
                  schemas: {Payload: {type: string}}
                  parameters:
                    Q: {name: q, in: query, schema: {type: string, default: target}}
                  requestBodies:
                    Body: {content: {application/json: {schema: {$ref: '#/components/schemas/Payload'}, example: target}}}
                  responses:
                    Common: {description: retained}
                  pathItems:
                    Base:
                      get: {responses: {'200': {$ref: '#/components/responses/Common'}}}
                    Composite:
                      $ref: '#/components/pathItems/Base'
                      post: {responses: {'200': {description: local}}}
                paths:
                  /root:
                    $ref: '#/components/pathItems/Composite'
                    put:
                      parameters:
                        - {$ref: '#/components/parameters/Q', name: ignored, in: header}
                      requestBody:
                        $ref: '#/components/requestBodies/Body'
                        description: ignored
                        content: {text/plain: {example: ignored}}
                      callbacks:
                        done:
                          '{$request.body#/callback}':
                            $ref: '#/components/pathItems/Base'
                            parameters: [{name: callbackId, in: query, schema: {type: string}}]
                            patch: {responses: {'200': {description: callback-local}}}
                      responses:
                        '200':
                          $ref: '#/components/responses/Common'
                          description: ignored
                  /blocked:
                    $ref: 'https://SECRET-CANARY.invalid/path-item'
                    post: {responses: {'200': {description: local}}}
                  /missing:
                    $ref: '#/components/pathItems/SECRET-CANARY'
                    delete: {responses: {'200': {description: local}}}
                """);
        ApiCollection imported30 = new OpenApiParser().parse(source30.toFile());
        assertThat(imported30.requests).extracting(r -> r.method + " " + r.path)
                .containsExactly("PUT /root", "POST /blocked", "DELETE /missing");
        assertThat(imported30.requests.get(0).parameters).extracting(p -> p.key + ":" + p.location)
                .contains("q:query");
        assertThat(imported30.requests.get(0).body.raw).isEqualTo("\"target\"");

        Path nativeFile = tempDir.resolve("path-item-native.json");
        Files.writeString(nativeFile, new GsonBuilder().create().toJson(ApiWorkbenchCollectionExporter.build(
                imported30, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        assertThat(nativeRoundTrip.requests.get(0).sourceMetadata.get("openapi.pathItem.structures"))
                .contains("$ref");
        assertThat(nativeRoundTrip.sourceMetadata.get("openapi.document.components"))
                .contains("pathItems", "Composite");

        ArrayList<String> warnings30 = new ArrayList<>();
        Map<String, Object> exported30 = OpenApiCollectionExporter.build(
                nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), warnings30);
        Map<String, Object> paths30 = cast(exported30.get("paths"));
        assertThat(cast(paths30.get("/root")))
                .containsEntry("$ref", "#/components/pathItems/Composite")
                .containsKey("put");
        assertThat(cast(paths30.get("/blocked"))).doesNotContainKey("$ref").containsKey("post");
        assertThat(cast(paths30.get("/missing"))).doesNotContainKey("$ref").containsKey("delete");
        Map<String, Object> componentPathItems = cast(cast(exported30.get("components")).get("pathItems"));
        assertThat(cast(componentPathItems.get("Composite")))
                .containsEntry("$ref", "#/components/pathItems/Base")
                .containsKey("post");
        Map<String, Object> put = cast(cast(paths30.get("/root")).get("put"));
        Map<String, Object> callback = cast(cast(cast(put.get("callbacks")).get("done"))
                .get("{$request.body#/callback}"));
        assertThat(callback).containsEntry("$ref", "#/components/pathItems/Base")
                .containsKeys("parameters", "patch");
        assertThat(cast(cast(put.get("responses")).get("200")))
                .containsOnly(entry("$ref", "#/components/responses/Common"));
        assertThat(collectRefs(exported30)).allMatch(ref -> ref.startsWith("#"));
        assertInternalRefsResolve(exported30);
        assertThat(warnings30).anyMatch(w -> w.contains("blocked or dangling reference"));
        assertThat(warnings30).allMatch(w -> !w.contains("SECRET-CANARY")
                && !w.contains("\r") && !w.contains("\n"));

        Path source31 = tempDir.resolve("path-item-31.yaml");
        Files.writeString(source31, """
                openapi: 3.1.0
                info: {title: Webhooks, version: '1'}
                components:
                  pathItems:
                    Base: {get: {responses: {'200': {description: base}}}}
                    Composite:
                      $ref: '#/components/pathItems/Base'
                      post: {responses: {'200': {description: component-local}}}
                webhooks:
                  event:
                    $ref: '#/components/pathItems/Composite'
                    trace: {responses: {'200': {description: webhook-local}}}
                paths:
                  /root31:
                    $ref: '#/components/pathItems/Composite'
                    put:
                      callbacks:
                        again:
                          '{$request.body#/callback}':
                            $ref: '#/components/pathItems/Base'
                            head: {responses: {'200': {description: callback-local}}}
                      responses: {'200': {description: local}}
                """);
        ApiCollection imported31 = new OpenApiParser().parse(source31.toFile());
        Path native31 = tempDir.resolve("path-item-31-native.json");
        Files.writeString(native31, new GsonBuilder().create().toJson(ApiWorkbenchCollectionExporter.build(
                imported31, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip31 = new ApiWorkbenchCollectionParser().parse(native31.toFile());
        assertThat(nativeRoundTrip31.sourceMetadata.get("openapi.document.webhooks"))
                .contains("event", "$ref", "trace");
        Map<String, Object> exported31 = OpenApiCollectionExporter.build(
                nativeRoundTrip31, options(CollectionExportFormat.OPENAPI_JSON), new ArrayList<>());
        Map<String, Object> webhook = cast(cast(exported31.get("webhooks")).get("event"));
        assertThat(webhook).containsEntry("$ref", "#/components/pathItems/Composite").containsKey("trace");
        Map<String, Object> root31 = cast(cast(exported31.get("paths")).get("/root31"));
        assertThat(root31).containsEntry("$ref", "#/components/pathItems/Composite").containsKey("put");
        Map<String, Object> callback31 = cast(cast(cast(cast(root31.get("put")).get("callbacks")).get("again"))
                .get("{$request.body#/callback}"));
        assertThat(callback31).containsEntry("$ref", "#/components/pathItems/Base").containsKey("head");
        assertInternalRefsResolve(exported31);
    }

    @Test
    void editedEndpointsOverrideStaleServerAndPathMetadata() throws Exception {
        Path source = tempDir.resolve("edited-endpoints.yaml");
        Files.writeString(source, """
                openapi: 3.0.3
                info: {title: Edited, version: '1'}
                servers: [{url: https://root.example.test/api}]
                paths:
                  /root/{id}:
                    get:
                      parameters: [{name: id, in: path, required: true, schema: {type: string, default: old}}]
                      responses: {'200': {description: ok}}
                  /path/{id}:
                    servers: [{url: http://path.example.test:8080/base}]
                    get:
                      parameters: [{name: id, in: path, required: true, schema: {type: string, default: old}}]
                      responses: {'200': {description: ok}}
                  /operation/{id}:
                    get:
                      servers: [{url: https://operation.example.test:9443/op}]
                      parameters: [{name: id, in: path, required: true, schema: {type: string, default: old}}]
                      responses: {'200': {description: ok}}
                """);
        ApiCollection imported = new OpenApiParser().parse(source.toFile());
        for (int i = 0; i < imported.requests.size(); i++) {
            ApiRequest request = imported.requests.get(i);
            request.url = switch (i) {
                case 0 -> "http://edited-root.example.test:8181/new/root/{{slug}}";
                case 1 -> "https://edited-path.example.test:8282/new/path/{{slug}}";
                default -> "http://edited-operation.example.test:8383/new/operation/{{slug}}";
            };
            request.parameters.clear();
            ApiRequest.Parameter slug = new ApiRequest.Parameter("path", "slug", "current");
            slug.required = true; slug.valuePresent = true; slug.style = "simple"; slug.explode = false;
            request.parameters.add(slug);
        }

        Path nativeFile = tempDir.resolve("edited-native.json");
        Files.writeString(nativeFile, new GsonBuilder().create().toJson(ApiWorkbenchCollectionExporter.build(
                imported, options(CollectionExportFormat.API_WORKBENCH_JSON), new ArrayList<>())));
        ApiCollection nativeRoundTrip = new ApiWorkbenchCollectionParser().parse(nativeFile.toFile());
        assertThat(nativeRoundTrip.requests).allMatch(r -> r.sourceMetadata.containsKey("openapi.endpointFingerprint"));
        assertThat(nativeRoundTrip.requests.get(1).sourceMetadata).containsKey("openapi.pathItem.servers");

        ArrayList<String> warnings = new ArrayList<>();
        Path exportedFile = tempDir.resolve("edited-export.json");
        Files.writeString(exportedFile, new GsonBuilder().create().toJson(OpenApiCollectionExporter.build(
                nativeRoundTrip, options(CollectionExportFormat.OPENAPI_JSON), warnings)));
        ApiCollection reimported = new OpenApiParser().parse(exportedFile.toFile());
        String[] expectedLines = {
                "GET /new/root/current HTTP/1.1", "GET /new/path/current HTTP/1.1", "GET /new/operation/current HTTP/1.1"};
        String[] expectedHosts = {
                "edited-root.example.test:8181", "edited-path.example.test:8282", "edited-operation.example.test:8383"};
        for (int i = 0; i < reimported.requests.size(); i++) {
            String raw = new String(new RequestBuilder(null).buildRequest(
                    reimported.requests.get(i), new VariableResolver()));
            assertThat(raw).startsWith(expectedLines[i] + "\r\n")
                    .contains("\r\nHost: " + expectedHosts[i] + "\r\n");
        }
        assertThat(warnings.stream().filter(w -> w.contains("operation-level server approximation"))).hasSize(1);
    }

    private static byte[] buildWithCollectionVariables(ApiCollection collection, ApiRequest request) throws Exception {
        VariableResolver resolver = new VariableResolver();
        resolver.addAll(collection.environment);
        resolver.addRequestVariables(request);
        return new RequestBuilder(null).buildRequest(request, resolver);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertDialectConformant(Map<String, Object> artifact) {
        String dialect = String.valueOf(artifact.get("openapi"));
        walkSchemas(artifact, schema -> {
            if ("3.0.3".equals(dialect)) {
                assertThat(schema).isNotInstanceOf(Boolean.class);
                if (schema instanceof Map<?, ?> map) {
                    assertThat(map.get("type")).isNotInstanceOf(List.class);
                    assertThat(map.keySet().stream().map(String::valueOf).toList()).doesNotContainAnyElementsOf(Set.of(
                            "$schema", "const", "contentEncoding", "contentMediaType", "prefixItems",
                            "dependentSchemas", "unevaluatedProperties", "unevaluatedItems"));
                }
            }
        });
    }

    private static void walkSchemas(Object node, java.util.function.Consumer<Object> assertion) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("schema".equals(key)) walkSchema(entry.getValue(), assertion);
                else if ("schemas".equals(key) && entry.getValue() instanceof Map<?, ?> schemas) {
                    schemas.values().forEach(schema -> walkSchema(schema, assertion));
                } else walkSchemas(entry.getValue(), assertion);
            }
        } else if (node instanceof List<?> list) list.forEach(item -> walkSchemas(item, assertion));
    }

    private static void walkSchema(Object schema, java.util.function.Consumer<Object> assertion) {
        assertion.accept(schema);
        if (!(schema instanceof Map<?, ?> map)) return;
        for (String key : List.of("items", "additionalProperties", "not", "unevaluatedProperties", "unevaluatedItems")) {
            if (map.containsKey(key)) walkSchema(map.get(key), assertion);
        }
        for (String key : List.of("properties", "dependentSchemas")) {
            if (map.get(key) instanceof Map<?, ?> children) children.values().forEach(child -> walkSchema(child, assertion));
        }
        for (String key : List.of("prefixItems", "allOf", "oneOf", "anyOf")) {
            if (map.get(key) instanceof List<?> children) children.forEach(child -> walkSchema(child, assertion));
        }
    }

    private static List<String> collectRefs(Object node) {
        List<String> refs = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("$ref".equals(String.valueOf(entry.getKey())) && entry.getValue() != null) refs.add(String.valueOf(entry.getValue()));
                else refs.addAll(collectRefs(entry.getValue()));
            }
        } else if (node instanceof List<?> list) for (Object item : list) refs.addAll(collectRefs(item));
        return refs;
    }

    private static void assertInternalRefsResolve(Map<String, Object> root) {
        for (String ref : collectRefs(root)) {
            assertThat(ref).startsWith("#");
            Object current = root;
            String pointer = ref.substring(1);
            for (String raw : pointer.substring(1).split("/", -1)) {
                String token = raw.replace("~1", "/").replace("~0", "~");
                assertThat(current).isInstanceOf(Map.class);
                Map<?, ?> map = (Map<?, ?>) current;
                assertThat(map.containsKey(token)).isTrue();
                current = map.get(token);
            }
        }
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
