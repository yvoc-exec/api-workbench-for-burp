package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiParserTest {

    @Test
    void canParseReturnsFalseForNonOpenApiYaml() throws Exception {
        Path tempYaml = createTempSpecFile("""
                name: not an api
                version: 1
                """);

        assertThat(new OpenApiParser().canParse(tempYaml.toFile())).isFalse();
    }

    @Test
    void canParseReturnsTrueForOpenApiYaml() throws Exception {
        Path tempYaml = createTempSpecFile("""
                openapi: 3.0.0
                info:
                  title: Demo
                  version: 1.0.0
                paths: {}
                """);

        assertThat(new OpenApiParser().canParse(tempYaml.toFile())).isTrue();
    }

    @Test
    void parseConvertsPathParamsAndAddsRequestVariables() throws Exception {
        Path tempYaml = createTempSpecFile("""
                openapi: 3.0.0
                info:
                  title: Demo
                  version: 1.0.0
                servers:
                  - url: https://api.example.com
                paths:
                  /users/{id}:
                    get:
                      operationId: getUser
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                          example: 42
                """);

        ApiCollection collection = new OpenApiParser().parse(tempYaml.toFile());
        ApiRequest req = collection.requests.get(0);

        assertThat(req.url).contains("/users/{{id}}");
        assertThat(req.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("id=42");
    }

    @Test
    void parseAppendsQueryParams() throws Exception {
        Path tempYaml = createTempSpecFile("""
                openapi: 3.0.0
                info:
                  title: Demo
                  version: 1.0.0
                servers:
                  - url: https://api.example.com/base?existing=1
                paths:
                  /users/{id}:
                    get:
                      operationId: searchUsers
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                          default: 7
                        - name: includeInactive
                          in: query
                          schema:
                            type: boolean
                          default: true
                        - name: search
                          in: query
                          schema:
                            type: string
                          example: alice
                """);

        ApiCollection collection = new OpenApiParser().parse(tempYaml.toFile());
        ApiRequest req = collection.requests.get(0);

        assertThat(req.url).contains("/users/{{id}}");
        assertThat(req.url).contains("existing=1");
        assertThat(req.url).contains("includeInactive=true");
        assertThat(req.url).contains("search=alice");
        assertThat(req.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("id=7");
    }

    private Path createTempSpecFile(String content) throws Exception {
        Path tempFile = Files.createTempFile(Path.of("target"), "openapi-parser-", ".yaml").toAbsolutePath().normalize();
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
