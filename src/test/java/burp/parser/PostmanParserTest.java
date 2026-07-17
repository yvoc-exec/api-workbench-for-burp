package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class PostmanParserTest {

    private ApiCollection parsePostman(String json) throws Exception {
        File file = File.createTempFile("postman-auth", ".json");
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        try {
            return new PostmanParser().parse(file);
        } finally {
            file.delete();
        }
    }

    @Test
    void reconstructsUrlObjectWhenRawIsMissing() throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "postman-url-object-", ".json");
        Files.writeString(file, """
                {
                  "info": {
                    "name": "URL Object Collection",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "Get User",
                      "request": {
                        "method": "GET",
                        "url": {
                          "protocol": "https",
                          "host": ["api", "example", "test"],
                          "path": ["users", "{{user_id}}"],
                          "query": [
                            {"key": "include", "value": "roles"},
                            {"key": "disabled", "value": "true", "disabled": true}
                          ]
                        }
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new PostmanParser().parse(file.toFile());

        assertThat(collection.requests).hasSize(1);
        ApiRequest request = collection.requests.get(0);
        assertThat(request.url)
                .isEqualTo("https://api.example.test/users/{{user_id}}?include=roles");
        assertThat(request.parameters).hasSize(2);
        assertThat(request.parameters.get(0).key).isEqualTo("include");
        assertThat(request.parameters.get(0).disabled).isFalse();
        assertThat(request.parameters.get(1).key).isEqualTo("disabled");
        assertThat(request.parameters.get(1).disabled).isTrue();
    }

    @Test
    void preservesCollectionFolderAndRequestScriptsWithDialects() throws Exception {
        ApiCollection collection = parsePostman("""
                {
                  "info": {
                    "name": "Script Demo",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "event": [
                    {
                      "listen": "prerequest",
                      "script": {
                        "type": "text/javascript",
                        "exec": ["console.log('collection pre');"]
                      }
                    }
                  ],
                  "item": [
                    {
                      "name": "Auth",
                      "event": [
                        {
                          "listen": "test",
                          "script": {
                            "exec": "console.log('folder test');"
                          }
                        }
                      ],
                      "item": [
                        {
                          "name": "Login",
                          "request": {
                            "method": "POST",
                            "url": "https://api.example.test/login"
                          },
                          "event": [
                            {
                              "listen": "prerequest",
                              "script": {
                                "exec": ["pm.environment.set('token', 'abc');"]
                              }
                            },
                            {
                              "listen": "test",
                              "script": {
                                "exec": "pm.test('ok', function () {});"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        assertThat(collection.scriptBlocks).hasSize(1);
        assertThat(collection.scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(collection.scriptBlocks.get(0).phase).isEqualTo(ScriptPhase.PRE_REQUEST);
        assertThat(collection.scriptBlocks.get(0).scope).isEqualTo(ScriptScope.COLLECTION);
        assertThat(collection.folderScriptBlocks.get("Auth")).hasSize(1);
        assertThat(collection.folderScriptBlocks.get("Auth").get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(collection.folderScriptBlocks.get("Auth").get(0).phase).isEqualTo(ScriptPhase.POST_RESPONSE);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.preRequestScripts).hasSize(1);
        assertThat(req.postResponseScripts).hasSize(1);
        assertThat(req.scriptBlocks).hasSize(2);
        assertThat(req.scriptBlocks.get(0).dialect).isEqualTo(ScriptDialect.POSTMAN);
        assertThat(req.scriptBlocks.get(0).phase).isEqualTo(ScriptPhase.PRE_REQUEST);
        assertThat(req.scriptBlocks.get(0).scope).isEqualTo(ScriptScope.REQUEST);
        assertThat(req.scriptBlocks.get(1).phase).isEqualTo(ScriptPhase.POST_RESPONSE);
    }

    @Test
    void parsesFormDataFileItemIntoExplicitFileUploadMetadata() throws Exception {
        Path tempJson = Files.createTempFile(Path.of("target"), "postman-", ".json").toAbsolutePath().normalize();
        String json = """
            {
              "info": { "name": "Collection", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
              "item": [
                {
                  "name": "Upload",
                  "request": {
                    "method": "POST",
                    "url": "https://example.com/upload",
                    "body": {
                      "mode": "formdata",
                      "formdata": [
                        { "key": "avatar", "type": "file", "src": "/tmp/avatar.png" }
                      ]
                    }
                  }
                }
              ]
            }
            """;
        Files.writeString(tempJson, json, StandardCharsets.UTF_8);
        tempJson.toFile().deleteOnExit();

        PostmanParser parser = new PostmanParser();
        ApiCollection collection = parser.parse(tempJson.toFile());
        ApiRequest req = collection.requests.get(0);
        ApiRequest.Body.FormField field = req.body.formdata.get(0);

        assertThat(field.type).isEqualTo("file");
        assertThat(field.fileUpload).isTrue();
        assertThat(field.filePath).isEqualTo("/tmp/avatar.png");
    }

    @Test
    void requestInheritsCollectionBearerAuthWhenRequestAuthMissing() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}"}]},
              "item": [
                {"name": "Get Me", "request": {"method": "GET", "url": "https://api.example.test/me"}}
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(collection.auth).isNotNull();
        assertThat(collection.auth.type).isEqualTo("bearer");
        assertThat(collection.folderAuthModes).isEmpty();
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("bearer");
        assertThat(req.auth.properties.get("token")).isEqualTo("{{accessToken}}");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authExplicitlyDisabled).isFalse();
        assertThat(req.authSource).isEqualTo("collection: Auth Demo");
    }

    @Test
    void nestedFolderAuthOverridesCollectionAuthForChildRequest() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Admin",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{adminToken}}"}]},
                  "item": [
                    {"name": "List Users", "request": {"method": "GET", "url": "https://api.example.test/admin/users"}}
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(collection.folderAuthModes).containsEntry("Admin", "explicit");
        assertThat(collection.folderAuth.get("Admin").type).isEqualTo("bearer");
        assertThat(req.auth.type).isEqualTo("bearer");
        assertThat(req.auth.properties.get("token")).isEqualTo("{{adminToken}}");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authSource).isEqualTo("folder: Admin");
    }

    @Test
    void explicitRequestAuthOverridesInheritedFolderAuth() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Admin",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{adminToken}}"}]},
                  "item": [
                    {
                      "name": "Special",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.test/special",
                        "auth": {"type": "basic", "basic": [{"key": "username", "value": "u"}, {"key": "password", "value": "p"}]}
                      }
                    }
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.auth.type).isEqualTo("basic");
        assertThat(req.auth.properties.get("username")).isEqualTo("u");
        assertThat(req.authOverrideMode).isEqualTo("explicit");
        assertThat(req.explicitAuth).isNotNull();
        assertThat(req.explicitAuth.type).isEqualTo("basic");
        assertThat(req.authInherited).isFalse();
        assertThat(req.authExplicitlyDisabled).isFalse();
        assertThat(req.authSource).isEqualTo("request: Special");
    }

    @Test
    void explicitNoAuthStopsParentInheritance() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Public",
                  "request": {
                    "method": "GET",
                    "url": "https://api.example.test/public",
                    "auth": {"type": "noauth"}
                  }
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("none");
        assertThat(req.hasAuth()).isFalse();
        assertThat(req.authOverrideMode).isEqualTo("none");
        assertThat(req.explicitAuth).isNotNull();
        assertThat(req.explicitAuth.type).isEqualTo("none");
        assertThat(req.authInherited).isFalse();
        assertThat(req.authExplicitlyDisabled).isTrue();
        assertThat(req.authSource).isEqualTo("request: Public");
    }

    @Test
    void folderNoAuthProvenanceIsPreservedForDescendants() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Public",
                  "auth": {"type": "noauth"},
                  "item": [
                    {
                      "name": "Child",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.test/public/child"
                      }
                    }
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.hasAuth()).isFalse();
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("none");
        assertThat(collection.folderAuthModes).containsEntry("Public", "none");
        assertThat(collection.folderAuth.get("Public").type).isEqualTo("none");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authExplicitlyDisabled).isTrue();
        assertThat(req.authSource).isEqualTo("folder: Public");
    }

    @Test
    void preservesPostmanQueryOrderDuplicatesDescriptionsAndDisabledRows() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?tag=one&tag=two",
                   "query":[
                     {"key":"tag","value":"one","description":{"content":"first"},"type":"text"},
                     {"key":"skip","value":"x","disabled":true,"description":"disabled"},
                     {"key":"tag","value":"two","description":"second"}
                   ]}}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.key).containsExactly("tag", "skip", "tag");
        assertThat(request.parameters).extracting(p -> p.disabled).containsExactly(false, true, false);
        assertThat(request.parameters).extracting(p -> p.description).containsExactly("first", "disabled", "second");
        assertThat(request.parameters.get(0).type).isEqualTo("text");
        assertThat(request.url).isEqualTo("https://example.test/a?tag=one&tag=two");
    }

    @Test
    void parsesPrimitiveUrlQueryIntoFirstClassParameters() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":"https://example.test/a?x=1&x=2"}}]}
                """).requests.get(0);
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("x", "x");
        assertThat(request.parameters).extracting(p -> p.value).containsExactly("1", "2");
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.raw");
    }

    @Test
    void preservesDisabledUrlEncodedBodyFields() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"POST","url":"https://example.test/a",
                 "body":{"mode":"urlencoded","urlencoded":[
                   {"key":"on","value":"1","type":"text"},
                   {"key":"off","value":"2","type":"number","disabled":true}
                 ]}}}]}
                """).requests.get(0);
        assertThat(request.body.urlencoded).hasSize(2);
        assertThat(request.body.urlencoded.get(1).disabled).isTrue();
        assertThat(request.body.urlencoded.get(1).type).isEqualTo("number");
    }

    @Test
    void preservesDisabledMultipartFileFields() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"POST","url":"https://example.test/a",
                 "body":{"mode":"formdata","formdata":[
                   {"key":"file","type":"file","src":["","/tmp/a.txt"],"disabled":true}
                 ]}}}]}
                """).requests.get(0);
        ApiRequest.Body.FormField field = request.body.formdata.get(0);
        assertThat(field.disabled).isTrue();
        assertThat(field.fileUpload).isTrue();
        assertThat(field.type).isEqualTo("file");
        assertThat(field.filePath).isEqualTo("/tmp/a.txt");
    }

    @Test
    void retainsBareAndExplicitlyEmptyQueryValues() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?flag&empty=",
                   "query":[{"key":"flag"},{"key":"empty","value":""}]
                 }}}]}
                """).requests.get(0);
        assertThat(request.parameters).extracting(p -> p.valuePresent).containsExactly(false, true);
        assertThat(request.url).isEqualTo("https://example.test/a?flag&empty=");
    }

    @Test
    void importsOrderedPostmanPathVariablesWithMetadataAndValuePresence() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/users/:id/{bare}",
                   "variable":[
                     {"key":"id","value":"42","type":"string","description":{"content":"identifier"}},
                     {"key":"bare","disabled":true},
                     {"key":"empty","value":""}
                   ]}}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.location).containsExactly("path", "path", "path");
        assertThat(request.parameters).extracting(p -> p.key).containsExactly("id", "bare", "empty");
        assertThat(request.parameters).extracting(p -> p.valuePresent).containsExactly(true, false, true);
        assertThat(request.parameters).extracting(p -> p.disabled).containsExactly(false, true, false);
        assertThat(request.parameters.get(0).description).isEqualTo("identifier");
        assertThat(request.parameters.get(0).type).isEqualTo("string");
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.variable");
        assertThat(request.url).isEqualTo("https://example.test/users/:id/{bare}");
    }

    @Test
    void importsCollectionVariableTypeAndEnabledState() throws Exception {
        ApiCollection collection = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "variable":[
                   {"key":"secret","value":"x","type":"secret","enabled":false},
                   {"key":"disabled","value":"y","type":"number","disabled":true},
                   {"key":"active","value":"z"}],"item":[]}
                """);
        assertThat(collection.variables).extracting(v -> v.key).containsExactly("secret", "disabled", "active");
        assertThat(collection.variables).extracting(v -> v.type).containsExactly("secret", "number", null);
        assertThat(collection.variables).extracting(v -> v.enabled).containsExactly(false, false, true);
    }

    @Test
    void disabledStructuredQueryConsumesMatchingRawWithoutReactivation() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?skip=x",
                   "query":[{"key":"skip","value":"x","disabled":true}]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(1);
        ApiRequest.Parameter parameter = request.parameters.get(0);
        assertThat(parameter.key).isEqualTo("skip");
        assertThat(parameter.disabled).isTrue();
        assertThat(parameter.source).isEqualTo("postman:url.query");
        assertThat(parameter.rawKey).isEqualTo("skip");
        assertThat(parameter.rawValue).isEqualTo("x");
        assertThat(request.url).isEqualTo("https://example.test/a");
        assertThat(request.parameters).extracting(p -> p.source)
                .doesNotContain("postman:url.raw-unmatched");
    }

    @Test
    void reorderedStructuredQueryConsumesRawRowsWithoutDuplicates() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?a=1&b=2",
                   "query":[{"key":"b","value":"2"},{"key":"a","value":"1"}]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.key).containsExactly("b", "a");
        assertThat(request.parameters).filteredOn(p -> "a".equals(p.key)).hasSize(1);
        assertThat(request.parameters).filteredOn(p -> "b".equals(p.key)).hasSize(1);
        assertThat(request.url).isEqualTo("https://example.test/a?b=2&a=1");
    }

    @Test
    void duplicateOccurrencesConsumeRawRowsOneForOne() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?tag=x&tag=x",
                   "query":[
                     {"key":"tag","value":"x","description":"first"},
                     {"key":"tag","value":"x","description":"second"}
                   ]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(2);
        assertThat(request.parameters).extracting(p -> p.description).containsExactly("first", "second");
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.query");
        assertThat(request.url).isEqualTo("https://example.test/a?tag=x&tag=x");
    }

    @Test
    void rawOnlyPostmanUrlObjectImportsEachRawRowOnce() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?a=1&a=2"
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.value).containsExactly("1", "2");
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.raw");
        assertThat(request.parameters).extracting(p -> p.source)
                .doesNotContain("postman:url.raw-unmatched");
    }

    @Test
    void disabledAndEnabledEquivalentStructuredRowsDoNotCreateExtraOccurrence() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?skip=x&skip=x",
                   "query":[
                     {"key":"skip","value":"x","disabled":true},
                     {"key":"skip","value":"x"}
                   ]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(2);
        assertThat(request.parameters).extracting(p -> p.disabled).containsExactly(true, false);
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.query");
        assertThat(request.url).isEqualTo("https://example.test/a?skip=x");
    }

    @Test
    void reorderedBareAndEmptyRowsPreserveStructuredValuePresence() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?x=&x",
                   "query":[
                     {"key":"x","description":"bare"},
                     {"key":"x","value":"","description":"empty"}
                   ]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(2);
        assertThat(request.parameters).extracting(p -> p.valuePresent).containsExactly(false, true);
        assertThat(request.parameters).extracting(p -> p.description).containsExactly("bare", "empty");
        assertThat(request.parameters).extracting(p -> p.source).containsOnly("postman:url.query");
        assertThat(request.url).isEqualTo("https://example.test/a?x&x=");
    }

    @Test
    void oppositeReorderedBareAndEmptyRowsPreserveStructuredValuePresence() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?x&x=",
                   "query":[{"key":"x","value":""},{"key":"x"}]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.valuePresent).containsExactly(true, false);
        assertThat(request.url).isEqualTo("https://example.test/a?x=&x");
    }

    @Test
    void valuePresenceMismatchConsumesStaleRawWithoutDuplicate() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?x=","query":[{"key":"x"}]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(1);
        assertThat(request.parameters.get(0).valuePresent).isFalse();
        assertThat(request.parameters.get(0).source).isEqualTo("postman:url.query");
        assertThat(request.url).isEqualTo("https://example.test/a?x");
    }

    @Test
    void disabledValuePresenceMismatchDoesNotReactivateRawRow() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?x=","query":[{"key":"x","disabled":true}]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).hasSize(1);
        assertThat(request.parameters.get(0).disabled).isTrue();
        assertThat(request.parameters.get(0).valuePresent).isFalse();
        assertThat(request.url).isEqualTo("https://example.test/a");
    }

    @Test
    void structuredEmptyAndWhitespaceKeysRemainOrdered() throws Exception {
        ApiRequest request = parsePostman("""
                {"info":{"name":"C","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"R","request":{"method":"GET","url":{
                   "raw":"https://example.test/a?=one&%20=two&normal=three",
                   "query":[
                     {"key":"","value":"one"},
                     {"key":" ","value":"two"},
                     {"key":"normal","value":"three"}
                   ]
                 }}}]}
                """).requests.get(0);

        assertThat(request.parameters).extracting(p -> p.key).containsExactly("", " ", "normal");
        assertThat(request.url).isEqualTo("https://example.test/a?=one&%20=two&normal=three");
    }

    @Test
    void parsesLargeGeneratedPostmanCollection() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int folder = 0; folder < 10; folder++) {
            if (folder > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"Folder ").append(folder).append("\",\"item\":[");
            for (int i = 0; i < 50; i++) {
                if (i > 0) {
                    items.append(',');
                }
                int id = folder * 50 + i;
                items.append("""
                        {"name":"Request %d","request":{"method":"GET","header":[{"key":"X-Request","value":"%d"}],"url":{"raw":"https://api.example.test/v1/items/%d?tenant={{tenant}}","protocol":"https","host":["api","example","test"],"path":["v1","items","%d"],"query":[{"key":"tenant","value":"{{tenant}}"}]}}}
                        """.formatted(id, id, id, id));
            }
            items.append("]}");
        }
        ApiCollection collection = parsePostman("""
                {"info":{"name":"Large Postman","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},"variable":[{"key":"tenant","value":"acme"}],"item":[%s]}
                """.formatted(items));

        assertThat(collection.requests).hasSize(500);
        assertThat(collection.requests.get(499).url).contains("/v1/items/499");
        assertThat(collection.requests.get(499).headers).extracting(header -> header.key).contains("X-Request");
    }
}
