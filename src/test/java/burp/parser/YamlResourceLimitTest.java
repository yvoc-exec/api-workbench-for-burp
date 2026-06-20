package burp.parser;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class YamlResourceLimitTest {

    @TempDir
    Path tempDir;

    @Test
    void recursiveOpenApiSchemasRemainBoundedAndProduceFiniteExamples() throws Exception {
        Path spec = tempDir.resolve("recursive.yaml");
        Files.writeString(spec, """
                openapi: 3.0.0
                info:
                  title: Recursive API
                  version: 1.0.0
                servers:
                  - url: https://api.example.test
                paths:
                  /tree:
                    post:
                      operationId: CreateTree
                      requestBody:
                        content:
                          application/json:
                            schema:
                              $ref: '#/components/schemas/Node'
                      responses:
                        '200':
                          description: ok
                components:
                  schemas:
                    Node:
                      type: object
                      required: [name, child]
                      properties:
                        name:
                          type: string
                        child:
                          $ref: '#/components/schemas/Node'
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new OpenApiParser().parse(spec.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).body.raw)
                .contains("\"name\"")
                .contains("recursive");
        assertThat(collection.requests.get(0).body.raw.length()).isLessThan(2000);
    }
}
