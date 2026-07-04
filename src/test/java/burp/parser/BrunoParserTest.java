package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .contains("if (res.status === 200) {")
                .contains("bru.setVar(\"done\", \"yes\");");
        assertThat(req.scriptBlocks)
                .hasSize(1);
        assertThat(req.scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.BRUNO);
        assertThat(req.scriptBlocks.get(0).phase).isEqualTo(ScriptPhase.POST_RESPONSE);
        assertThat(req.scriptBlocks.get(0).source)
                .contains("if (res.status === 200) {")
                .contains("bru.setVar(\"done\", \"yes\");");
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
    void lowercaseDeleteMethodIsDetectedAsRequest() throws Exception {
        ApiCollection collection = parseSingleRequestBru("delete");

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();
        assertThat(collection.requests.get(0).method).isEqualTo("DELETE");
        assertThat(collection.requests.get(0).url).isEqualTo("https://api.example.test/resource");
    }

    @Test
    void uppercaseDeleteMethodIsDetectedAsRequest() throws Exception {
        ApiCollection collection = parseSingleRequestBru("DELETE");

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();
        assertThat(collection.requests.get(0).method).isEqualTo("DELETE");
        assertThat(collection.requests.get(0).url).isEqualTo("https://api.example.test/resource");
    }

    @Test
    void mixedCaseDeleteMethodIsDetectedAsRequest() throws Exception {
        ApiCollection collection = parseSingleRequestBru("Delete");

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();
        assertThat(collection.requests.get(0).method).isEqualTo("DELETE");
        assertThat(collection.requests.get(0).url).isEqualTo("https://api.example.test/resource");
    }

    @Test
    void lowercaseCustomMethodIsDetectedAsRequest() throws Exception {
        ApiCollection collection = parseSingleRequestBru("propfind");

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();
        assertThat(collection.requests.get(0).method).isEqualTo("PROPFIND");
        assertThat(collection.requests.get(0).url).isEqualTo("https://api.example.test/resource");
    }

    @Test
    void mixedCaseCustomMethodIsDetectedAsRequest() throws Exception {
        ApiCollection collection = parseSingleRequestBru("mSearch");

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();
        assertThat(collection.requests.get(0).method).isEqualTo("MSEARCH");
        assertThat(collection.requests.get(0).url).isEqualTo("https://api.example.test/resource");
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
    void embeddedTripleApostrophesInHeadersRemainSingleLineValuesAndKeepBodyVisible() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Embedded Header Apostrophes
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/header
                }

                headers {
                  X-Pattern: prefix'''suffix
                }

                body:text {
                  request survived
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Pattern");
            assertThat(header.value).isEqualTo("prefix'''suffix");
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("request survived");
        assertThat(rawRequestText(request)).contains("X-Pattern: prefix'''suffix");
        assertThat(rawRequestText(request)).contains("request survived");
    }

    @Test
    void embeddedTripleApostrophesInVarsRemainSingleLineValuesAndResolveFully() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Embedded Vars Apostrophes
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/vars
                }

                vars {
                  token: abc'''def
                }

                headers {
                  X-Token: {{token}}
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.variables).singleElement().satisfies(variable -> {
            assertThat(variable.key).isEqualTo("token");
            assertThat(variable.value).isEqualTo("abc'''def");
        });

        VariableResolver resolver = new VariableResolver();
        resolver.addCollectionVariables(collection);
        resolver.addFolderVariables(collection, request);
        resolver.addRequestVariables(request);
        assertThat(resolver.resolve("{{token}}")).isEqualTo("abc'''def");
        assertThat(rawRequestText(request, resolver)).contains("X-Token: abc'''def");
    }

    @Test
    void embeddedTripleApostrophesInBearerAuthRemainSingleLineValuesAndKeepLaterHeadersVisible() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Embedded Bearer Apostrophes
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/auth
                }

                auth:bearer {
                  token: "'''"
                }

                headers {
                  X-After: yes
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "'''");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(rawRequestText(request)).contains("Authorization: Bearer '''");
    }

    @Test
    void closingDelimiterLineWithFakeHeadersDeclarationKeepsBodyVisible() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Closing Prefix Header
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/test
                }

                vars {
                  payload: '''
                line one
                headers {'''
                }

                body:text {
                  survived
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("survived");
        assertThat(rawRequestText(request)).contains("survived");
    }

    @Test
    void closingDelimiterLineStartingWithDeleteKeepsHeadersVisible() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Closing Delete Prefix
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/test
                }

                headers {
                  X-Description: '''
                line one
                delete {'''
                }

                body:text {
                  survived
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.method).isEqualTo("POST");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Description");
            assertThat(header.value).isEqualTo("'''");
        });
        assertThat(request.body.raw).isEqualTo("survived");
    }

    @Test
    void closingDelimiterLineStartingWithTypedDeclarationKeepsLaterHeadersVisible() throws Exception {
        ApiCollection collection = parseCollection("""
                meta {
                  name: Closing Typed Prefix
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/test
                }

                auth:bearer {
                  token: '''
                line one
                body:json {'''
                }

                headers {
                  X-After: yes
                }
                """);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
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

    @Test
    void brunoZipArchiveParsesLikeFolderImport() throws Exception {
        Path zip = createBrunoZip("bruno-archive", Map.of(
                "MyCollection/bruno.json", """
                        {
                          "name": "Zipped Bruno",
                          "vars": {
                            "baseUrl": "https://zip.example.test"
                          }
                        }
                        """,
                "MyCollection/folder/GetUsers.bru", """
                        meta {
                          name: Get Users
                          type: http
                          seq: 1
                        }

                        get {
                          url: {{baseUrl}}/users
                        }
                        """
        ));

        BrunoParser parser = new BrunoParser();
        assertThat(parser.canParse(zip.toFile())).isTrue();

        ApiCollection collection = parser.parse(zip.toFile());

        assertThat(collection.name).isEqualTo("Zipped Bruno");
        assertThat(collection.environment).containsEntry("baseUrl", "https://zip.example.test");
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).path).isEqualTo("folder/Get Users");
    }

    @Test
    void brunoZipArchiveIgnoresZipSlipEntries() throws Exception {
        Path zip = createBrunoZip("bruno-slip", new LinkedHashMap<>() {{
            put("../../escape.txt", "nope");
            put("MyCollection/bruno.json", """
                    {
                      "name": "Safe Bruno"
                    }
                    """);
            put("MyCollection/folder/GetUsers.bru", """
                    meta {
                      name: Get Users
                      type: http
                      seq: 1
                    }

                    get {
                      url: https://api.example.test/users
                    }
                    """);
        }});

        assertThatThrownBy(() -> new BrunoParser().parse(zip.toFile()))
                .isInstanceOf(ArchiveImportLimitException.class)
                .satisfies(throwable -> assertThat(((ArchiveImportLimitException) throwable).getReason())
                        .isEqualTo(ArchiveImportLimitException.Reason.UNSAFE_PATH));
    }

    private static Path createBrunoZip(String prefix, Map<String, String> entries) throws Exception {
        Path zip = Files.createTempFile(Path.of("target"), prefix, ".zip").toAbsolutePath().normalize();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        zip.toFile().deleteOnExit();
        return zip;
    }

    private ApiCollection parseCollection(String content) throws Exception {
        Path root = Files.createTempDirectory("bruno-collection");
        Files.writeString(root.resolve("Request.bru"), content);
        return new BrunoParser().parse(root.toFile());
    }

    private String rawRequestText(ApiRequest request) throws Exception {
        return rawRequestText(request, new VariableResolver());
    }

    private String rawRequestText(ApiRequest request, VariableResolver resolver) throws Exception {
        return new String(new RequestBuilder(null).buildRequest(request, resolver), StandardCharsets.UTF_8);
    }

    private ApiCollection parseSingleRequestBru(String methodToken) throws Exception {
        Path root = Files.createTempDirectory("bruno-method");
        Files.writeString(root.resolve("Request.bru"), """
                meta {
                  name: %s
                  type: http
                  seq: 1
                }

                %s {
                  url: https://api.example.test/resource
                }
                """.formatted(methodToken, methodToken));

        return new BrunoParser().parse(root.toFile());
    }
}
