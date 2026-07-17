package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiRequestBodyFidelityTest {
    @TempDir Path tempDir;

    @Test
    void ranksMediaRetainsAlternativesAndHonorsMediaExample() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.1.0
                info: {title: T, version: '1'}
                paths:
                  /x:
                    post:
                      requestBody:
                        required: true
                        description: payload
                        content:
                          text/plain: {example: text}
                          application/vnd.test+json: {example: {vendor: true}}
                          application/json: {examples: {z: {value: {x: 2}}, a: {value: {x: 1}}}}
                      responses: {'200': {description: ok}}
                """).requests.get(0);
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.contentType).isEqualTo("application/json");
        assertThat(request.body.raw).isEqualTo("{\"x\":1}");
        assertThat(request.body.required).isTrue();
        assertThat(request.body.description).isEqualTo("payload");
        assertThat(request.body.sourceMetadata.get("openapi.requestBody.content")).contains("text/plain");
    }

    @Test
    void importsUrlEncodedEncodingAndReadOnlyWriteOnlySchemaGeneration() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /x:
                    post:
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                            schema:
                              type: object
                              required: [write]
                              properties:
                                read: {type: string, readOnly: true, default: no}
                                write: {type: string, writeOnly: true, default: yes}
                                tags: {type: array, items: {type: string}, example: [a,b]}
                            encoding:
                              tags: {style: form, explode: false, allowReserved: true, contentType: text/plain}
                      responses: {'200': {description: ok}}
                """).requests.get(0);
        assertThat(request.body.mode).isEqualTo("urlencoded");
        assertThat(request.body.urlencoded).extracting(f -> f.key).containsExactly("write", "tags");
        ApiRequest.Body.FormField tags = request.body.urlencoded.get(1);
        assertThat(tags.style).isEqualTo("form");
        assertThat(tags.explode).isFalse();
        assertThat(tags.allowReserved).isTrue();
        assertThat(tags.contentType).isEqualTo("text/plain");
    }

    @Test
    void importsMultipartFilesMultipleFileApproximationAndWholeBodyBinary() throws Exception {
        ApiCollection collection = parse("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /upload:
                    post:
                      requestBody:
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                file: {type: string, format: binary}
                                files: {type: array, items: {type: string, format: binary}}
                      responses: {'200': {description: ok}}
                  /binary:
                    put:
                      requestBody:
                        content:
                          application/octet-stream: {schema: {type: string, format: binary}}
                      responses: {'200': {description: ok}}
                """);
        ApiRequest multipart = collection.requests.get(0);
        assertThat(multipart.body.formdata).allMatch(f -> f.fileUpload && "file".equals(f.type));
        ApiRequest binary = collection.requests.get(1);
        assertThat(binary.body.mode).isEqualTo("file");
        assertThat(binary.body.filePath).isEmpty();
        assertThat(collection.importWarnings).anyMatch(w -> w.contains("multiple-file placeholder approximation"))
                .anyMatch(w -> w.contains("binary body requires local file"));
    }

    @Test
    void importsSwaggerTwoBodyFormDataFileConsumesAndCollectionFormat() throws Exception {
        ApiCollection collection = parse("""
                swagger: '2.0'
                info: {title: T, version: '1'}
                host: api.example.test
                consumes: [multipart/form-data]
                paths:
                  /form:
                    post:
                      parameters:
                        - {name: upload, in: formData, type: file, required: true}
                        - {name: tags, in: formData, type: array, items: {type: string}, collectionFormat: multi, default: [a,b]}
                      responses: {'200': {description: ok}}
                  /body:
                    put:
                      consumes: [application/json]
                      parameters:
                        - name: payload
                          in: body
                          required: true
                          schema: {type: object, properties: {name: {type: string, default: n}}}
                      responses: {'200': {description: ok}}
                """);
        ApiRequest form = collection.requests.get(0);
        assertThat(form.body.mode).isEqualTo("formdata");
        assertThat(form.body.formdata.get(0).fileUpload).isTrue();
        assertThat(form.body.formdata.get(1).style).isEqualTo("form");
        assertThat(form.body.formdata.get(1).explode).isTrue();
        ApiRequest body = collection.requests.get(1);
        assertThat(body.body.mode).isEqualTo("raw");
        assertThat(body.body.raw).isEqualTo("{\"name\":\"n\"}");
        assertThat(body.body.required).isTrue();
    }

    @Test
    void importsOpenApiThirtyOneContentEncodingAsMultipartFile() throws Exception {
        ApiRequest request = parse("""
                openapi: 3.1.0
                info: {title: T, version: '1'}
                paths:
                  /x:
                    post:
                      requestBody:
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                encoded: {type: string, contentEncoding: base64, contentMediaType: application/pdf}
                      responses: {'200': {description: ok}}
                """).requests.get(0);
        assertThat(request.body.formdata.get(0).fileUpload).isTrue();
        assertThat(request.body.formdata.get(0).filePath).isEmpty();
    }

    private ApiCollection parse(String yaml) throws Exception {
        Path file = tempDir.resolve("body-" + System.nanoTime() + ".yaml");
        Files.writeString(file, yaml);
        return new OpenApiParser().parse(file.toFile());
    }
}
