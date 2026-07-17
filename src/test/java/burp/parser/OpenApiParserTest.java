package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
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
        assertThat(req.parameters)
                .extracting(parameter -> parameter.location + ":" + parameter.key + "=" + parameter.value)
                .contains("path:id=42");
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
        assertThat(req.parameters)
                .extracting(parameter -> parameter.location + ":" + parameter.key + "=" + parameter.value)
                .containsExactly("query:existing=1", "path:id=7", "query:includeInactive=true", "query:search=alice");
    }

    @Test
    void topLevelSecurityBecomesCollectionAuthInheritedByOperations() throws Exception {
        ApiCollection collection = parseOpenApiJson("""
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Demo", "version": "1.0"},
                  "servers": [{"url": "https://api.example.test"}],
                  "components": {
                    "securitySchemes": {
                      "bearerAuth": {"type": "http", "scheme": "bearer"}
                    }
                  },
                  "security": [{"bearerAuth": []}],
                  "paths": {
                    "/me": {
                      "get": {"operationId": "GetMe", "responses": {"200": {"description": "ok"}}}
                    }
                  }
                }
                """);

        ApiRequest request = collection.requests.get(0);
        assertThat(collection.auth).isNotNull();
        assertThat(collection.auth.type).isEqualTo("bearer");
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.authInherited).isTrue();
        assertThat(request.authOverrideMode).isEqualTo("inherit");
        assertThat(request.authSource).isEqualTo("collection: Demo");
    }

    @Test
    void operationSecurityOverridesTopLevelSecurityAndSurvivesRecompute() throws Exception {
        ApiCollection collection = parseOpenApiJson("""
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Demo", "version": "1.0"},
                  "servers": [{"url": "https://api.example.test"}],
                  "components": {
                    "securitySchemes": {
                      "bearerAuth": {"type": "http", "scheme": "bearer"},
                      "basicAuth": {"type": "http", "scheme": "basic"}
                    }
                  },
                  "security": [{"bearerAuth": []}],
                  "paths": {
                    "/admin": {
                      "get": {
                        "operationId": "Admin",
                        "security": [{"basicAuth": []}],
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """);

        ApiRequest request = collection.requests.get(0);
        assertThat(request.auth.type).isEqualTo("basic");
        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.explicitAuth.type).isEqualTo("basic");
        assertThat(request.authInherited).isFalse();

        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.auth.type).isEqualTo("basic");
        assertThat(request.authSource).isEqualTo("request: Admin");
    }

    @Test
    void operationEmptySecurityStopsTopLevelSecurityInheritance() throws Exception {
        ApiCollection collection = parseOpenApiJson("""
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Demo", "version": "1.0"},
                  "servers": [{"url": "https://api.example.test"}],
                  "components": {
                    "securitySchemes": {
                      "bearerAuth": {"type": "http", "scheme": "bearer"}
                    }
                  },
                  "security": [{"bearerAuth": []}],
                  "paths": {
                    "/public": {
                      "get": {
                        "operationId": "Public",
                        "security": [],
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """);

        ApiRequest request = collection.requests.get(0);
        assertThat(request.hasAuth()).isFalse();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authSource).isEqualTo("request: Public");
    }

    @Test
    void generatesJsonExampleFromLocalSchemaRef() throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "openapi-ref-", ".json");
        Files.writeString(file, """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Ref API", "version": "1"},
                  "servers": [{"url": "https://api.example.test"}],
                  "paths": {
                    "/users": {
                      "post": {
                        "operationId": "Create User",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/UserCreate"}
                            }
                          }
                        },
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "UserCreate": {
                        "type": "object",
                        "required": ["email"],
                        "properties": {
                          "email": {"type": "string", "format": "email"},
                          "active": {"type": "boolean"}
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new OpenApiParser().parse(file.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).body.raw)
                .contains("\"email\"")
                .contains("user@example.com")
                .contains("\"active\"");
        assertThat(collection.requests.get(0).body.raw).doesNotContain("\"$ref\"");
    }

    @Test
    void parsesLargeGeneratedOpenApiSpec() throws Exception {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                paths.append(',');
            }
            paths.append("""
                    "/items/%d": {
                      "get": {
                        "operationId": "getItem%d",
                        "parameters": [
                          {"name": "trace", "in": "header", "schema": {"type": "string"}, "example": "t-%d"}
                        ],
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                    """.formatted(i, i, i));
        }
        ApiCollection collection = parseOpenApiJson("""
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Large OpenAPI", "version": "1.0"},
                  "servers": [{"url": "https://api.example.test"}],
                  "paths": {%s}
                }
                """.formatted(paths));

        assertThat(collection.requests).hasSize(250);
        assertThat(collection.requests.get(249).url).contains("/items/249");
        assertThat(collection.requests.get(249).parameters)
                .filteredOn(parameter -> "header".equals(parameter.location))
                .extracting(parameter -> parameter.key).contains("trace");
    }

    private Path createTempSpecFile(String content) throws Exception {
        Path tempFile = Files.createTempFile(Path.of("target"), "openapi-parser-", ".yaml").toAbsolutePath().normalize();
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    private ApiCollection parseOpenApiJson(String json) throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "openapi-auth-", ".json").toAbsolutePath().normalize();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        return new OpenApiParser().parse(file.toFile());
    }
}
