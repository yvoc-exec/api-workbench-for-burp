package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiParameterFidelityTest {
    @TempDir Path tempDir;

    @Test
    void mergesPathAndOperationParametersByCaseSensitiveNameAndLocation() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.1.0
                info: {title: T, version: '1'}
                paths:
                  /users/{id}:
                    parameters:
                      - {name: q, in: query, description: path, schema: {type: string, default: p}}
                      - {name: id, in: path, required: true, schema: {type: integer, default: 1}}
                      - {name: q, in: header, schema: {type: string, default: h}}
                    get:
                      parameters:
                        - {name: q, in: query, description: operation, schema: {type: string, default: o}}
                        - {name: session, in: cookie, schema: {type: string, default: c}}
                      responses: {'200': {description: ok}}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.key + ":" + p.location)
                .containsExactly("q:query", "id:path", "q:header", "session:cookie");
        assertThat(request.parameters.get(0).description).isEqualTo("operation");
        assertThat(request.parameters.get(0).source).isEqualTo("openapi:operation");
    }

    @Test
    void retainsFourLocationsSerializationMetadataAndDisabledUnknowns() throws Exception {
        ApiCollection collection = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x/{id}:
                    get:
                      parameters:
                        - name: id
                          in: path
                          required: false
                          schema: {type: string, format: uuid, default: abc}
                        - {name: tags, in: query, style: pipeDelimited, explode: false, allowReserved: true, schema: {type: array, items: {type: string}, example: [a,b]}}
                        - {name: Accept, in: header, schema: {type: string, default: json}}
                        - {name: sid, in: cookie, x-disabled: true, schema: {type: string, default: c}}
                        - {name: odd, in: custom, schema: {type: string, default: x}}
                      responses: {'200': {description: ok}}
                """);
        ApiRequest request = collection.requests.get(0);

        assertThat(request.parameters).hasSize(5);
        ApiRequest.Parameter path = request.parameters.get(0);
        assertThat(path.required).isTrue();
        assertThat(path.type).isEqualTo("string");
        assertThat(path.format).isEqualTo("uuid");
        assertThat(path.style).isEqualTo("simple");
        assertThat(path.explode).isFalse();
        assertThat(request.parameters.get(1).value).isEqualTo("[\"a\",\"b\"]");
        assertThat(request.parameters.get(2).disabled).isTrue();
        assertThat(request.parameters.get(3).disabled).isTrue();
        assertThat(request.parameters.get(4).disabled).isTrue();
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("invalid path required state"))
                .anyMatch(w -> w.contains("ignored standard header parameter"))
                .anyMatch(w -> w.contains("unsupported parameter location"));
    }

    @Test
    void parameterContentAndLexicalExamplesUseDeterministicPrecedence() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    get:
                      parameters:
                        - name: q
                          in: query
                          examples:
                            z: {value: last}
                            a: {value: first}
                          schema: {type: string, default: fallback}
                        - name: obj
                          in: query
                          content:
                            text/plain: {schema: {type: string, default: text}}
                            application/json: {schema: {type: object, default: {a: 1}}}
                      responses: {'200': {description: ok}}
                """).requests.get(0);
        assertThat(request.parameters).extracting(p -> p.value).containsExactly("first", "{\"a\":1}");
        assertThat(request.parameters.get(1).sourceMetadata).containsKey("openapi.content");
    }

    @Test
    void duplicateDefinitionsKeepFirstPositionAndLastDefinitionWhileOperationOverridesInPlace() throws Exception {
        ApiCollection collection = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    parameters:
                      - {name: q, in: query, schema: {type: string, default: path-first}}
                      - {name: h, in: header, schema: {type: string, default: h}}
                      - {name: q, in: query, schema: {type: string, default: path-last}}
                    get:
                      parameters:
                        - {name: q, in: query, schema: {type: string, default: operation-first}}
                        - {name: c, in: cookie, schema: {type: string, default: c}}
                        - {name: q, in: query, schema: {type: string, default: operation-last}}
                      responses: {'200': {description: ok}}
                """);
        assertThat(collection.requests.get(0).parameters).extracting(p -> p.key + "=" + p.value)
                .containsExactly("q=operation-last", "h=h", "c=c");
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("duplicate parameter identity: path-level"))
                .anyMatch(w -> w.contains("duplicate parameter identity: operation-level"))
                .anyMatch(w -> w.contains("operation parameter override"));
    }

    @Test
    void referencedStructuredSchemasKeepSemanticsForEveryTransportLocation() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                components:
                  schemas:
                    Values: {type: array, format: csv-array, items: {type: string}}
                    Object: {type: object, format: named-object, additionalProperties: {type: string}}
                paths:
                  /x/{ids}:
                    get:
                      parameters:
                        - {name: tags, in: query, style: form, explode: true, example: [a, b], schema: {$ref: '#/components/schemas/Values'}}
                        - {name: filter, in: query, style: deepObject, explode: true, example: {a: '1'}, schema: {$ref: '#/components/schemas/Object'}}
                        - {name: ids, in: path, required: true, style: simple, explode: false, example: [one, two], schema: {$ref: '#/components/schemas/Values'}}
                        - {name: X-Object, in: header, style: simple, explode: true, example: {a: '1', b: '2'}, schema: {$ref: '#/components/schemas/Object'}}
                        - {name: ignored, in: cookie, style: form, explode: true, example: {c: '3', d: '4'}, schema: {$ref: '#/components/schemas/Object'}}
                        - name: contentValues
                          in: query
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Values'}
                              example: [x, y]
                      responses: {'200': {description: ok}}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.type)
                .containsExactly("array", "object", "array", "object", "object", "array");
        assertThat(request.parameters).extracting(p -> p.format)
                .containsExactly("csv-array", "named-object", "csv-array", "named-object", "named-object", "csv-array");
        assertThat(request.parameters).extracting(p -> p.value)
                .containsExactly("[\"a\",\"b\"]", "{\"a\":\"1\"}", "[\"one\",\"two\"]",
                        "{\"a\":\"1\",\"b\":\"2\"}", "{\"c\":\"3\",\"d\":\"4\"}", "[\"x\",\"y\"]");

        String raw = new String(new RequestBuilder(null).buildRequest(request, new VariableResolver()));
        assertThat(raw).startsWith("GET /x/one,two?tags=a&tags=b&filter[a]=1&contentValues=x&contentValues=y HTTP/1.1\r\n")
                .contains("\r\nX-Object: a=1,b=2\r\n")
                .contains("\r\nCookie: c=3; d=4\r\n");
    }

    private ApiCollection parse(String yaml) throws Exception {
        Path file = tempDir.resolve("spec-" + System.nanoTime() + ".yaml");
        Files.writeString(file, yaml);
        return new OpenApiParser().parse(file.toFile());
    }
}
