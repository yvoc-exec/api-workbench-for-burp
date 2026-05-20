package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoParserTest {

    @Test
    void parseKeepsNestedBracesInBodyAndPostResponseScript() throws Exception {
        Path tempBru = Files.createTempFile(Path.of("target"), "bruno-", ".bru").toAbsolutePath().normalize();
        String bru = """
                meta {
                  name: Nested Body
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.com/items
                  body {
                    {
                      "filters": {
                        "status": "active",
                        "range": {
                          "from": 1,
                          "to": 10
                        }
                      }
                    }
                  }
                }

                script:post-response {
                  if (res.status === 200) {
                    bru.setVar("done", "yes");
                  }
                }
                """;
        Files.writeString(tempBru, bru, StandardCharsets.UTF_8);
        tempBru.toFile().deleteOnExit();

        ApiCollection collection = new BrunoParser().parse(tempBru.toFile());
        ApiRequest req = collection.requests.get(0);

        assertThat(req.body).isNotNull();
        assertThat(req.body.raw).contains("\"filters\"");
        assertThat(req.body.raw).contains("\"range\"");
        assertThat(req.body.raw).contains("\"to\": 10");
        assertThat(req.postResponseScripts)
                .hasSize(1)
                .first()
                .extracting(script -> script.exec)
                .asString()
                .contains("if (responseCode.code === 200) {")
                .contains("pm.environment.set(\"done\", \"yes\");");
    }

    @Test
    void metaNameIsUsedForFinalRequestPath() throws Exception {
        Path root = Files.createTempDirectory("bruno-meta-path");
        Path folder = Files.createDirectories(root.resolve("Admin"));
        Files.writeString(folder.resolve("get-users.bru"), """
                meta {
                  name: List Users
                  type: http
                  seq: 7
                }

                get {
                  url: https://api.example.test/users
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).name).isEqualTo("List Users");
        assertThat(collection.requests.get(0).path).isEqualTo("Admin/List Users");
    }

    @Test
    void parsedRequestAuthIsMarkedExplicitAndSurvivesRecompute() throws Exception {
        Path root = Files.createTempDirectory("bruno-auth-metadata");
        Files.writeString(root.resolve("secure.bru"), """
                meta {
                  name: Secure
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/secure
                }

                auth {
                  mode: bearer
                  token: {{requestToken}}
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.explicitAuth).isNotNull();
        assertThat(request.explicitAuth.type).isEqualTo("bearer");
        assertThat(request.authSource).isEqualTo("request: Secure");

        ApiRequest.Auth collectionAuth = new ApiRequest.Auth();
        collectionAuth.type = "bearer";
        collectionAuth.properties.put("token", "{{collectionToken}}");
        collection.auth = collectionAuth;
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "{{requestToken}}");
        assertThat(request.authInherited).isFalse();
    }

    @Test
    void explicitNoAuthBlocksCollectionAuthInheritance() throws Exception {
        Path root = Files.createTempDirectory("bruno-noauth-metadata");
        Files.writeString(root.resolve("public.bru"), """
                meta {
                  name: Public
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/public
                }

                auth {
                  mode: none
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        ApiRequest.Auth collectionAuth = new ApiRequest.Auth();
        collectionAuth.type = "bearer";
        collectionAuth.properties.put("token", "{{collectionToken}}");
        collection.auth = collectionAuth;
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.hasAuth()).isFalse();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.explicitAuth.type).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(request.authSource).isEqualTo("request: Public");
    }

    @Test
    void disabledHeadersArePreservedWithLeadingTildeStripped() throws Exception {
        Path root = Files.createTempDirectory("bruno-disabled-headers");
        Files.writeString(root.resolve("headers.bru"), """
                meta {
                  name: Headers
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/headers
                }

                headers {
                  X-Enabled: yes
                  ~X-Disabled: no
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        assertThat(request.headers).hasSize(2);
        assertThat(request.headers)
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("X-Enabled");
                    assertThat(header.value).isEqualTo("yes");
                    assertThat(header.disabled).isFalse();
                })
                .anySatisfy(header -> {
                    assertThat(header.key).isEqualTo("X-Disabled");
                    assertThat(header.value).isEqualTo("no");
                    assertThat(header.disabled).isTrue();
                });

        byte[] raw = new RequestBuilder(null).buildRequest(request, new VariableResolver());
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).contains("X-Enabled: yes");
        assertThat(text).doesNotContain("X-Disabled: no");
    }

    @Test
    void collectionMetadataBruImportsCollectionVariablesAndDoesNotBecomeARequest() throws Exception {
        Path root = Files.createTempDirectory("bruno-collection-vars");
        Files.writeString(root.resolve("MyCollection.bru"), """
                vars {
                  baseUrl: https://collection.example.test
                }
                """);
        Files.writeString(root.resolve("GetMe.bru"), """
                meta {
                  name: Get Me
                  type: http
                  seq: 1
                }

                get {
                  url: {{baseUrl}}/me
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.variables)
                .extracting(variable -> variable.key + "=" + variable.value)
                .contains("baseUrl=https://collection.example.test");
        assertThat(collection.requests.get(0).name).isEqualTo("Get Me");
    }

    @Test
    void folderMetadataBruImportsFolderVariables() throws Exception {
        Path root = Files.createTempDirectory("bruno-folder-vars");
        Path admin = Files.createDirectories(root.resolve("Admin"));
        Files.writeString(admin.resolve("Admin.bru"), """
                vars {
                  role: admin
                }
                """);
        Files.writeString(admin.resolve("GetUsers.bru"), """
                meta {
                  name: Get Users
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/users
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.folderVars).containsKey("Admin");
        assertThat(collection.folderVars.get("Admin")).containsEntry("role", "admin");
        assertThat(collection.requests.get(0).path).isEqualTo("Admin/Get Users");
    }

    @Test
    void folderMetadataVarsResolveNearestFolderAndRequestVarsOverrideThem() throws Exception {
        Path root = Files.createTempDirectory("bruno-folder-precedence");
        Path admin = Files.createDirectories(root.resolve("Admin"));
        Path nested = Files.createDirectories(admin.resolve("Nested"));
        Files.writeString(admin.resolve("Admin.bru"), """
                vars {
                  token: parent
                }
                """);
        Files.writeString(nested.resolve("Nested.bru"), """
                vars {
                  token: child
                }
                """);
        Files.writeString(nested.resolve("GetDetail.bru"), """
                meta {
                  name: Get Detail
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/detail/{{token}}
                }
                vars {
                  token: request
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        VariableResolver folderResolver = new VariableResolver();
        folderResolver.addCollectionVariables(collection);
        folderResolver.addFolderVariables(collection, request);
        assertThat(folderResolver.resolve("{{token}}")).isEqualTo("child");

        VariableResolver requestResolver = new VariableResolver();
        requestResolver.addCollectionVariables(collection);
        requestResolver.addFolderVariables(collection, request);
        requestResolver.addRequestVariables(request);
        assertThat(requestResolver.resolve("{{token}}")).isEqualTo("request");

        byte[] raw = new RequestBuilder(null).buildRequest(request, requestResolver);
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).contains("/detail/request");
    }

    @Test
    void existingBrunoJsonVarsStillWork() throws Exception {
        Path root = Files.createTempDirectory("bruno-json-vars");
        Files.writeString(root.resolve("bruno.json"), """
                {
                  "name": "Local",
                  "vars": {
                    "baseUrl": "https://json.example.test"
                  }
                }
                """);
        Files.writeString(root.resolve("Ping.bru"), """
                meta {
                  name: Ping
                  type: http
                  seq: 1
                }

                get {
                  url: {{baseUrl}}/ping
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        assertThat(collection.environment).containsEntry("baseUrl", "https://json.example.test");
        assertThat(collection.name).isEqualTo("Local");
    }

    @Test
    void indentedStandardMethodIsDetectedAsRequest() throws Exception {
        Path root = Files.createTempDirectory("bruno-indented-method");
        Files.writeString(root.resolve("Indented.bru"), """
                meta {
                  name: Indented
                  type: http
                  seq: 1
                }

                  get {
                    url: https://api.example.test/indented
                  }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).method).isEqualTo("GET");
        assertThat(collection.requests.get(0).path).isEqualTo("Indented");
    }

    @Test
    void connectMethodIsDetectedAsRequest() throws Exception {
        Path root = Files.createTempDirectory("bruno-connect-method");
        Files.writeString(root.resolve("Connect.bru"), """
                meta {
                  name: Connect
                  type: http
                  seq: 1
                }

                connect {
                  url: https://api.example.test/connect
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).method).isEqualTo("CONNECT");
    }

    @Test
    void customMethodIsDetectedWhenMetaSaysHttp() throws Exception {
        Path root = Files.createTempDirectory("bruno-custom-method");
        Files.writeString(root.resolve("Custom.bru"), """
                meta {
                  name: Custom
                  type: http
                  seq: 1
                }

                LIST {
                  url: https://api.example.test/list
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).method).isEqualTo("LIST");
    }

    @Test
    void metadataOnlyBruWithVarsStillBecomesCollectionVariablesNotRequests() throws Exception {
        Path root = Files.createTempDirectory("bruno-metadata-only");
        Files.writeString(root.resolve("MyCollection.bru"), """
                vars {
                  baseUrl: https://collection.example.test
                }
                """);

        ApiCollection collection = new BrunoParser().parse(root.toFile());

        assertThat(collection.requests).isEmpty();
        assertThat(collection.variables).extracting(variable -> variable.key + "=" + variable.value)
                .contains("baseUrl=https://collection.example.test");
    }
}
