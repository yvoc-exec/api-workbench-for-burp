package burp.parser;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiReferenceResolutionTest {
    @TempDir Path tempDir;

    @Test
    void resolvesLocalParameterSchemaExampleAndRequestBodyReferences() throws Exception {
        ApiCollection collection = parse("root.yaml", """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                components:
                  schemas: {User: {type: object, properties: {name: {type: string, default: n}}}}
                  examples: {Q: {value: selected}}
                  parameters: {Q: {name: q, in: query, examples: {one: {$ref: '#/components/examples/Q'}}, schema: {type: string}}}
                  requestBodies:
                    B: {content: {application/json: {schema: {$ref: '#/components/schemas/User'}}}}
                paths:
                  /x:
                    post:
                      parameters: [{$ref: '#/components/parameters/Q'}]
                      requestBody: {$ref: '#/components/requestBodies/B'}
                      responses: {'200': {description: ok}}
                """);
        assertThat(collection.requests.get(0).parameters.get(0).value).isEqualTo("selected");
        assertThat(collection.requests.get(0).body.raw).isEqualTo("{\"name\":\"n\"}");
    }

    @Test
    void resolvesChainedRelativeJsonYamlAndEscapedPointers() throws Exception {
        Files.writeString(tempDir.resolve("defs.json"), """
                {"components":{"a/b":{"~name":{"type":"string","default":"ok"}}}}
                """);
        Files.writeString(tempDir.resolve("parameter.yaml"), """
                name: q
                in: query
                schema: {$ref: 'defs.json#/components/a~1b/~0name'}
                """);
        ApiCollection collection = parse("root.yaml", """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    get:
                      parameters: [{$ref: 'parameter.yaml'}]
                      responses: {'200': {description: ok}}
                """);
        assertThat(collection.requests.get(0).parameters.get(0).value).isEqualTo("ok");
    }

    @Test
    void blocksRemoteFileAbsoluteTraversalMissingAndCyclesWithoutAbortingOtherOperations() throws Exception {
        Files.writeString(tempDir.resolve("cycle.yaml"), "$ref: 'cycle.yaml'\n");
        Path absolute = tempDir.resolve("outside.yaml").toAbsolutePath();
        ApiCollection collection = parse("root.yaml", """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    get:
                      parameters:
                        - {$ref: 'https://example.test/p.yaml'}
                        - {$ref: 'file:///tmp/p.yaml'}
                        - {$ref: 'ABS'}
                        - {$ref: '../escape.yaml'}
                        - {$ref: 'cycle.yaml'}
                        - {$ref: '#/missing'}
                        - {name: ok, in: query, schema: {type: string, default: yes}}
                      responses: {'200': {description: ok}}
                """.replace("ABS", absolute.toString().replace("\\", "/")));
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).parameters).extracting(p -> p.key).containsExactly("ok");
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("blocked or unresolved reference"))
                .anyMatch(w -> w.contains("reference cycle or limit"));
    }

    @Test
    void enforcesIndividualDocumentAndReferencedDocumentCountLimits() throws Exception {
        Path oversized = tempDir.resolve("oversized.yaml");
        Files.write(oversized, new byte[(int) OpenApiReferenceResolver.MAX_DOCUMENT_BYTES + 1]);
        StringBuilder parameters = new StringBuilder("        - {$ref: 'oversized.yaml'}\n");
        for (int i = 0; i < 65; i++) {
            Files.writeString(tempDir.resolve("p" + i + ".yaml"),
                    "name: p" + i + "\nin: query\nschema: {type: string, default: v}\n");
            parameters.append("        - {$ref: 'p").append(i).append(".yaml'}\n");
        }
        ApiCollection collection = parse("root.yaml", """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    get:
                      parameters:
                %s      responses: {'200': {description: ok}}
                """.formatted(parameters));
        assertThat(collection.requests.get(0).parameters.size()).isLessThanOrEqualTo(64);
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("document size"))
                .anyMatch(w -> w.contains("document count"));
    }

    @Test
    void resolvesRelativeStructuredSchemaSemanticsAndStoresPortableNestedTree() throws Exception {
        Files.writeString(tempDir.resolve("structured.yaml"), """
                Scalar: {type: string, format: uuid, default: abc}
                Array: {type: array, format: external-array, items: {$ref: '#/Scalar'}}
                Object:
                  allOf:
                    - {type: object, properties: {values: {$ref: '#/Array'}}}
                    - {type: object, properties: {name: {$ref: '#/Scalar'}}}
                """);
        ApiCollection collection = parse("root.yaml", """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    get:
                      parameters:
                        - name: values
                          in: query
                          style: form
                          explode: true
                          example: [one, two]
                          schema: {$ref: 'structured.yaml#/Array'}
                      responses: {'200': {description: ok}}
                """);
        var parameter = collection.requests.get(0).parameters.get(0);
        assertThat(parameter.type).isEqualTo("array");
        assertThat(parameter.format).isEqualTo("external-array");
        assertThat(parameter.value).isEqualTo("[\"one\",\"two\"]");
        assertThat(parameter.sourceMetadata.get("openapi.resolvedSchema"))
                .contains("external-array").doesNotContain("$ref");
    }

    private ApiCollection parse(String name, String yaml) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, yaml);
        return new OpenApiParser().parse(file.toFile());
    }
}
