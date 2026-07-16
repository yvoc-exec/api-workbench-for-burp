package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoParserTypedSyntaxTest {

    @TempDir
    Path tempDir;

    @Test
    void typedBearerAuthHeadersAndDisabledHeadersBuildAResolvedGetRequest() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Bearer Tokens
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/items
                }

                headers {
                  Authorization: Bearer {{token}}
                  X-After: yes
                  ~X-Disabled: no
                }

                auth:bearer {
                  token: {{token}}
                }
                """);

        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "{{token}}");
        assertThat(request.headers).extracting(h -> h.key).containsExactly("Authorization", "X-After", "X-Disabled");
        assertThat(request.headers).anySatisfy(header -> assertThat(header.disabled).isTrue());

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("token", "resolved-token");
        String raw = rawRequestText(request, resolver);

        assertThat(raw).contains("GET /items HTTP/1.1");
        assertThat(raw).contains("Authorization: Bearer resolved-token");
        assertThat(raw).contains("X-After: yes");
        assertThat(raw).doesNotContain("X-Disabled: no");
    }

    @Test
    void methodSelectorsChooseBearerAndJsonEvenWhenStaleBlocksAppearFirst() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Selector Priority
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/selector
                  body: json
                  auth: bearer
                }

                auth:basic {
                  username: ignored-user
                  password: ignored-pass
                }

                auth:bearer {
                  token: {{token}}
                }

                body:graphql {
                  query ExampleQuery {
                    viewer {
                      id
                    }
                  }
                }

                body:json {
                  {
                    "payload": "{{flow}}"
                  }
                }
                """);

        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "{{token}}");
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).contains("\"payload\": \"{{flow}}\"");

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("token", "bearer-token");
        resolver.addCustomVariable("flow", "resolved-flow");
        String raw = rawRequestText(request, resolver);

        assertThat(raw).contains("Authorization: Bearer bearer-token");
        assertThat(raw).contains("\"payload\": \"resolved-flow\"");
        assertThat(raw).doesNotContain("ignored-user");
        assertThat(raw).doesNotContain("ExampleQuery");
    }

    @Test
    void typedBasicApiKeyAndOauth2AuthModesAreImportedAndBuilt() throws Exception {
        ApiRequest basicRequest = parse("""
                meta {
                  name: Basic
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/basic
                }

                auth:basic {
                  username: user1
                  password: pass1
                }
                """);
        assertThat(rawRequestText(basicRequest, new VariableResolver())).contains("Authorization: Basic ");

        ApiRequest apiKeyRequest = parse("""
                meta {
                  name: ApiKey
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/apikey
                }

                auth:apikey {
                  key: X-Api-Key
                  value: key-123
                  in: header
                }
                """);
        assertThat(rawRequestText(apiKeyRequest, new VariableResolver())).contains("X-Api-Key: key-123");

        ApiRequest oauth2Request = parse("""
                meta {
                  name: OAuth2
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/oauth
                }

                auth:oauth2 {
                  accessToken: oauth-token
                }
                """);
        assertThat(rawRequestText(oauth2Request, new VariableResolver())).contains("Authorization: Bearer oauth-token");
    }

    @ParameterizedTest
    @MethodSource("authSelectorCases")
    void methodBlockAuthSelectorsChooseRequestedAuthEvenWhenStaleBlocksAppearFirst(String selector, String source) throws Exception {
        ApiRequest request = parse(source);

        assertThat(request.authOverrideMode).isEqualTo("explicit");
        assertThat(request.authInherited).isFalse();
        assertThat(request.authExplicitlyDisabled).isFalse();
        assertThat(request.auth).isNotNull();

        String raw = rawRequestText(request, new VariableResolver());
        switch (selector) {
            case "basic" -> {
                assertThat(request.auth.type).isEqualTo("basic");
                assertThat(request.auth.properties).containsEntry("username", "user1");
                assertThat(request.auth.properties).containsEntry("password", "pass1");
                assertThat(raw).contains("Authorization: Basic ");
                assertThat(raw).doesNotContain("stale-bearer");
            }
            case "apikey" -> {
                assertThat(request.auth.type).isEqualTo("apikey");
                assertThat(request.auth.properties).containsEntry("key", "X-Api-Key");
                assertThat(request.auth.properties).containsEntry("value", "key-123");
                assertThat(request.auth.properties).containsEntry("in", "header");
                assertThat(raw).contains("X-Api-Key: key-123");
                assertThat(raw).doesNotContain("stale-basic");
            }
            case "oauth2" -> {
                assertThat(request.auth.type).isEqualTo("oauth2");
                assertThat(request.auth.properties).containsEntry("accessToken", "oauth-token");
                assertThat(raw).contains("Authorization: Bearer oauth-token");
                assertThat(raw).doesNotContain("stale-bearer");
            }
            default -> throw new IllegalStateException("Unexpected selector: " + selector);
        }
    }

    private static Stream<Arguments> authSelectorCases() {
        return Stream.of(
                Arguments.of("basic", """
                        meta {
                          name: Basic Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/basic-selector
                          auth: basic
                        }

                        auth:bearer {
                          token: stale-bearer
                        }

                        auth:basic {
                          username: user1
                          password: pass1
                        }
                        """),
                Arguments.of("apikey", """
                        meta {
                          name: ApiKey Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/apikey-selector
                          auth: apikey
                        }

                        auth:basic {
                          username: stale-basic
                          password: stale-pass
                        }

                        auth:apikey {
                          key: X-Api-Key
                          value: key-123
                          in: header
                        }
                        """),
                Arguments.of("oauth2", """
                        meta {
                          name: OAuth2 Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/oauth-selector
                          auth: oauth2
                        }

                        auth:bearer {
                          token: stale-bearer
                        }

                        auth:oauth2 {
                          accessToken: oauth-token
                        }
                        """)
        );
    }

    @Test
    void typedJsonBodyWithNestedObjectsAndTemplateTokensBuildsJsonPostRequest() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Nested Json
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/users
                }

                headers {
                  X-Trace: {{trace_id}}
                }

                body:json {
                  {
                    "user": {
                      "id": "{{userId}}",
                      "profile": {
                        "name": "Jane"
                      }
                    }
                  }
                }
                """);

        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).contains("\"profile\"");
        assertThat(request.body.raw).contains("\"id\": \"{{userId}}\"");
        assertThat(request.body.contentType).isEqualTo("application/json");

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("userId", "123");
        resolver.addCustomVariable("trace_id", "trace-9");
        String raw = rawRequestText(request, resolver);

        assertThat(raw).contains("POST /users HTTP/1.1");
        assertThat(raw).contains("Content-Type: application/json");
        assertThat(raw).contains("\"id\": \"123\"");
        assertThat(raw).contains("X-Trace: trace-9");
        assertThat(raw).contains("\"profile\"");
    }

    @Test
    void typedTextBodyUsesTextPlainAndXmlBodyRespectsAuthoredContentType() throws Exception {
        ApiRequest textRequest = parse("""
                meta {
                  name: Plain Text
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/text
                }

                body:text {
                  plain text body
                }
                """);
        assertThat(textRequest.body).isNotNull();
        assertThat(textRequest.body.mode).isEqualTo("raw");
        assertThat(textRequest.body.contentType).isEqualTo("text/plain");
        assertThat(rawRequestText(textRequest, new VariableResolver())).contains("Content-Type: text/plain");

        ApiRequest xmlRequest = parse("""
                meta {
                  name: Xml Body
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/xml
                }

                headers {
                  Content-Type: text/xml
                }

                body:xml {
                  <item><name>{{name}}</name></item>
                }
                """);
        assertThat(xmlRequest.body).isNotNull();
        assertThat(xmlRequest.body.mode).isEqualTo("raw");
        assertThat(xmlRequest.body.contentType).isEqualTo("text/xml");

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("name", "Widget");
        String raw = rawRequestText(xmlRequest, resolver);
        assertThat(raw).contains("Content-Type: text/xml");
        assertThat(raw).contains("<name>Widget</name>");
    }

    @Test
    void typedGraphqlBodyAndVariablesArePreserved() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: GraphQL
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/graphql
                }

                body:graphql {
                  query GetUser {
                    user(id: "{{userId}}") {
                      id
                      name
                    }
                  }
                }

                body:graphql:vars {
                  {
                    "userId": "abc-123"
                  }
                }
                """);

        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("graphql");
        assertThat(request.body.graphql).isNotNull();
        assertThat(request.body.graphql.query).contains("query GetUser");
        assertThat(request.body.graphql.variables).contains("\"userId\"");

        String raw = rawRequestText(request, new VariableResolver());
        assertThat(raw).contains("Content-Type: application/json");
        assertThat(raw).contains("\"query\"");
        assertThat(raw).contains("\"variables\"");
    }

    @Test
    void typedUrlEncodedBodyBuildsFormPostRequestAndPreservesDisabledFields() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Urlencoded
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/form
                }

                body:form-urlencoded {
                  grant_type: client_credentials
                  client_id: client-123
                  ~skip_me: no
                }
                """);

        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("urlencoded");
        assertThat(request.body.urlencoded).hasSize(3);
        assertThat(request.body.urlencoded).anySatisfy(field -> assertThat(field.disabled).isTrue());

        String raw = rawRequestText(request, new VariableResolver());
        assertThat(raw).contains("POST /form HTTP/1.1");
        assertThat(raw).contains("Content-Type: application/x-www-form-urlencoded");
        assertThat(raw).contains("grant_type=client_credentials");
        assertThat(raw).contains("client_id=client-123");
        assertThat(raw).doesNotContain("skip_me");
    }

    @Test
    void typedMultipartBodyBuildsMultipartRequestWithTextAndFileFields() throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "upload-", ".txt");
        Files.writeString(file, "file-content", StandardCharsets.UTF_8);

        ApiRequest request = parse("""
                meta {
                  name: Multipart
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/upload
                  body: multipart-form
                }

                body:json {
                  {
                    "stale": true
                  }
                }

                body:multipart-form {
                  description: sample upload
                  upload: @file(%s)
                }
                """.formatted(file.toString()));

        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("formdata");
        assertThat(request.body.formdata).hasSize(2);
        assertThat(request.body.formdata).anySatisfy(field -> assertThat(field.fileUpload).isTrue());

        String raw = rawRequestText(request, new VariableResolver());
        assertThat(raw).contains("POST /upload HTTP/1.1");
        assertThat(raw).contains("multipart/form-data; boundary=");
        assertThat(raw).contains("name=\"description\"");
        assertThat(raw).contains("name=\"upload\"; filename=\"");
        assertThat(raw).contains("file-content");
        assertThat(raw).doesNotContain("stale");
    }

    @ParameterizedTest
    @MethodSource("bodySelectorCases")
    void methodBlockBodySelectorsChooseRequestedBodyEvenWhenStaleBlocksAppearFirst(String selector, String source) throws Exception {
        ApiRequest request = parse(source);
        VariableResolver resolver = new VariableResolver();
        if ("xml".equals(selector)) {
            resolver.addCustomVariable("name", "Widget");
        }
        String raw = rawRequestText(request, resolver);

        switch (selector) {
            case "text" -> {
                assertThat(request.body).isNotNull();
                assertThat(request.body.mode).isEqualTo("raw");
                assertThat(request.body.raw).isEqualTo("plain text body");
                assertThat(request.body.contentType).isEqualTo("text/plain");
                assertThat(raw).contains("Content-Type: text/plain");
                assertThat(raw).contains("plain text body");
                assertThat(raw).doesNotContain("stale-json");
            }
            case "xml" -> {
                assertThat(request.body).isNotNull();
                assertThat(request.body.mode).isEqualTo("raw");
                assertThat(request.body.raw).contains("<item><name>{{name}}</name></item>");
                assertThat(request.body.contentType).isEqualTo("text/xml");
                assertThat(raw).contains("Content-Type: text/xml");
                assertThat(raw).contains("<name>Widget</name>");
                assertThat(raw).doesNotContain("stale-text");
            }
            case "graphql" -> {
                assertThat(request.body).isNotNull();
                assertThat(request.body.mode).isEqualTo("graphql");
                assertThat(request.body.graphql).isNotNull();
                assertThat(request.body.graphql.query).contains("query GetUser");
                assertThat(request.body.graphql.variables).contains("\"userId\": \"abc-123\"");
                assertThat(request.body.contentType).isEqualTo("application/json");
                assertThat(raw).contains("\"query\"");
                assertThat(raw).contains("\"variables\"");
                assertThat(raw).doesNotContain("stale-json");
            }
            case "form-urlencoded" -> {
                assertThat(request.body).isNotNull();
                assertThat(request.body.mode).isEqualTo("urlencoded");
                assertThat(request.body.urlencoded).extracting(field -> field.key).containsExactly("grant_type", "client_id");
                assertThat(request.body.urlencoded).extracting(field -> field.value).containsExactly("client_credentials", "client-123");
                assertThat(request.body.contentType).isEqualTo("application/x-www-form-urlencoded");
                assertThat(raw).contains("grant_type=client_credentials");
                assertThat(raw).contains("client_id=client-123");
                assertThat(raw).doesNotContain("stale-json");
            }
            default -> throw new IllegalStateException("Unexpected selector: " + selector);
        }
    }

    private static Stream<Arguments> bodySelectorCases() {
        return Stream.of(
                Arguments.of("text", """
                        meta {
                          name: Text Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/text-selector
                          body: text
                        }

                        body:json {
                          {
                            "stale-json": true
                          }
                        }

                        body:text {
                          plain text body
                        }
                        """),
                Arguments.of("xml", """
                        meta {
                          name: Xml Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/xml-selector
                          body: xml
                        }

                        body:text {
                          stale-text
                        }

                        headers {
                          Content-Type: text/xml
                        }

                        body:xml {
                          <item><name>{{name}}</name></item>
                        }
                        """),
                Arguments.of("graphql", """
                        meta {
                          name: GraphQL Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/graphql-selector
                          body: graphql
                        }

                        body:json {
                          {
                            "stale-json": true
                          }
                        }

                        body:graphql {
                          query GetUser {
                            user(id: "{{userId}}") {
                              id
                              name
                            }
                          }
                        }

                        body:graphql:vars {
                          {
                            "userId": "abc-123"
                          }
                        }
                        """),
                Arguments.of("form-urlencoded", """
                        meta {
                          name: Urlencoded Selector
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/form-selector
                          body: form-urlencoded
                        }

                        body:text {
                          stale-json
                        }

                        body:form-urlencoded {
                          grant_type: client_credentials
                          client_id: client-123
                        }
                        """)
        );
    }

    @Test
    void typedNoAuthBlocksCollectionAuthAfterRecompute() throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.name = "Auth";
        collection.format = "bruno";

        ApiRequest request = parse("""
                meta {
                  name: Public
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/public
                }

                auth:none {
                }
                """);

        collection.requests.add(request);
        ApiRequest.Auth collectionAuth = new ApiRequest.Auth();
        collectionAuth.type = "bearer";
        collectionAuth.properties.put("token", "collection-token");
        collection.auth = collectionAuth;
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(rawRequestText(request, new VariableResolver())).doesNotContain("Authorization:");
    }

    @Test
    void typedNoAuthAndNoBodySelectorsBlockInheritanceAndStaleBlocks() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("noauth-selector"));
        Files.writeString(root.resolve("bruno.json"), """
                {
                  "name": "NoAuthSelector"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Collection.bru"), """
                auth:bearer {
                  token: collection-token
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Request.bru"), """
                meta {
                  name: NoAuth
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/public
                  auth: none
                  body: none
                }

                auth:bearer {
                  token: should-not-apply
                }

                body:json {
                  {
                    "stale": true
                  }
                }
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        assertThat(collection.auth).isNotNull();
        assertThat(collection.auth.type).isEqualTo("bearer");
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(request.hasAuth()).isFalse();
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("none");
        assertThat(request.body.raw).isNull();

        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authInherited).isFalse();

        String raw = rawRequestText(request, new VariableResolver());
        assertThat(raw).doesNotContain("Authorization:");
        assertThat(raw).doesNotContain("stale");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "noauth", "no_auth"})
    void methodBlockNoAuthAliasesCanonicalizeAndBlockInheritance(String selector) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("noauth-alias-" + selector));
        Files.writeString(root.resolve("bruno.json"), """
                {
                  "name": "NoAuthAliases"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Collection.bru"), """
                auth:bearer {
                  token: collection-token
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Request.bru"), """
                meta {
                  name: NoAuth Alias
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/public
                  auth: %s
                }

                auth:bearer {
                  token: stale-request-token
                }
                """.formatted(selector), StandardCharsets.UTF_8);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        ApiRequest request = collection.requests.get(0);

        ApiRequest.Auth collectionAuth = collection.auth;
        assertThat(collectionAuth).isNotNull();
        assertThat(collectionAuth.type).isEqualTo("bearer");

        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(request.hasAuth()).isFalse();

        assertThat(collection.importWarnings).isNullOrEmpty();

        String raw = rawRequestText(request, new VariableResolver());
        assertThat(raw).doesNotContain("Authorization:");
    }

    @Test
    void quotedDictionaryKeysArePreservedForHeadersAndFormFields() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Quoted Keys
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/quoted
                }

                headers {
                  "key with spaces": value:with:colons
                  "colon:header": header:value
                  ~"disabled:colon:header": disabled:value:with:colon
                  "nested escaped \\"quote\\"": q:value
                }

                body:form-urlencoded {
                  "field with spaces": value:with:colons
                  "colon:field": field:value:with:colons
                  ~"disabled:colon:field": disabled:field:value
                }
                """);

        assertThat(request.headers).extracting(h -> h.key).containsExactly(
                "key with spaces",
                "colon:header",
                "disabled:colon:header",
                "nested escaped \"quote\""
        );
        assertThat(request.headers).extracting(h -> h.value).containsExactly(
                "value:with:colons",
                "header:value",
                "disabled:value:with:colon",
                "q:value"
        );
        assertThat(request.headers).extracting(h -> h.disabled).containsExactly(false, false, true, false);

        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("urlencoded");
        assertThat(request.body.urlencoded).extracting(f -> f.key).containsExactly(
                "field with spaces",
                "colon:field",
                "disabled:colon:field"
        );
        assertThat(request.body.urlencoded).extracting(f -> f.value).containsExactly(
                "value:with:colons",
                "field:value:with:colons",
                "disabled:field:value"
        );
        assertThat(request.body.urlencoded).extracting(f -> f.disabled).containsExactly(false, false, true);
    }

    @Test
    void multipartQuotedDictionaryKeysArePreservedAndInvalidHeaderNamesStayImportedOnly() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Multipart Quoted Keys
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/multipart
                }

                headers {
                  "invalid header name": value:with:colons
                }

                body:multipart-form {
                  "field with spaces": text:value:with:colons
                  "colon:field": field:value:with:colons
                  ~"disabled:colon:field": disabled:field:value:with:colon
                }
                """);

        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("invalid header name");
            assertThat(header.value).isEqualTo("value:with:colons");
            assertThat(header.disabled).isFalse();
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("formdata");
        assertThat(request.body.formdata).extracting(f -> f.key).containsExactly(
                "field with spaces",
                "colon:field",
                "disabled:colon:field"
        );
        assertThat(request.body.formdata).extracting(f -> f.value).containsExactly(
                "text:value:with:colons",
                "field:value:with:colons",
                "disabled:field:value:with:colon"
        );
        assertThat(request.body.formdata).extracting(f -> f.disabled).containsExactly(false, false, true);
    }

    @Test
    void scriptBlocksKeepOriginalBrunoSourceAndDialect() throws Exception {
        ApiRequest request = parse("""
                meta {
                  name: Scripts
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/scripts
                }

                script:pre-request {
                  bru.setVar('pre', 'value');
                  req.headers.upsert('X-Pre', 'yes');
                }

                script:post-response {
                  bru.test('status', function () {
                    bru.expect(res.status).to.equal('200');
                  });
                }
                """);

        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('pre', 'value');")
                .contains("req.headers.upsert('X-Pre', 'yes');");
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.test('status', function () {")
                .contains("bru.expect(res.status).to.equal('200');");
        assertThat(request.scriptBlocks)
                .hasSize(2)
                .allSatisfy(block -> assertThat(block.dialect).isEqualTo(ScriptDialect.BRUNO));
        assertThat(request.scriptBlocks)
                .extracting(block -> block.source)
                .anySatisfy(source -> assertThat(source).contains("bru.setVar('pre', 'value');"))
                .anySatisfy(source -> assertThat(source).contains("bru.test('status', function () {"));
        assertThat(request.scriptBlocks)
                .extracting(block -> block.phase)
                .containsExactlyInAnyOrder(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE);
    }

    @Test
    void multipartTextFileAndDisabledRowsPreserveMetadata() throws Exception {
        ApiRequest request = parse("""
                post { url: https://example.test/upload }
                body:multipart-form {
                  name: value
                  ~name: disabled
                  upload: @file(C:\\files\\one.bin)
                  ~upload: @file(C:\\files\\two.bin)
                  "": blank
                }
                """);
        assertThat(request.body.formdata).extracting(f -> f.key)
                .containsExactly("name", "name", "upload", "upload", "");
        assertThat(request.body.formdata).extracting(f -> f.disabled)
                .containsExactly(false, true, false, true, false);
        assertThat(request.body.formdata.get(2).type).isEqualTo("file");
        assertThat(request.body.formdata.get(2).fileUpload).isTrue();
        assertThat(request.body.formdata.get(2).filePath).isEqualTo("C:\\files\\one.bin");
        assertThat(request.body.formdata.get(2).value).isEqualTo("@file(C:\\files\\one.bin)");
    }

    @Test
    void requestVarsPreserveOrderDuplicatesAndEnabledState() throws Exception {
        ApiRequest request = parse("""
                get { url: https://example.test/a }
                vars {
                  token: one
                  ~token: two
                  token: three
                }
                """);
        assertThat(request.variables).extracting(v -> v.key + ":" + v.value + ":" + v.enabled)
                .containsExactly("token:one:true", "token:two:false", "token:three:true");
    }

    @Test
    void allBrunoScriptBlocksPreserveSourceOrderAndPhases() throws Exception {
        ApiRequest request = parse("""
                get { url: https://example.test/a }
                script:post-response { postOne(); }
                script:pre-request { pre(); }
                test { verify(); }
                script:post-response { postTwo(); }
                """);
        assertThat(request.scriptBlocks).hasSize(4);
        assertThat(request.scriptBlocks).extracting(b -> b.order).containsExactly(0, 1, 2, 3);
        assertThat(request.scriptBlocks).extracting(b -> b.phase)
                .containsExactly(ScriptPhase.POST_RESPONSE, ScriptPhase.PRE_REQUEST,
                        ScriptPhase.TEST, ScriptPhase.POST_RESPONSE);
        assertThat(request.scriptBlocks).allSatisfy(b -> assertThat(b.dialect).isEqualTo(ScriptDialect.BRUNO));
        assertThat(request.preRequestScripts).hasSize(1);
        assertThat(request.postResponseScripts).hasSize(3);
    }

    @Test
    void disabledFolderVariableIsNotActivatedAndWarns() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("Admin"));
        Files.writeString(folder.resolve("_folder.bru"), "vars { ~secret: sensitive-value }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("request.bru"), "get { url: https://example.test/a }", StandardCharsets.UTF_8);
        ApiCollection collection = new BrunoParser().parse(tempDir.toFile());
        assertThat(collection.folderVars.getOrDefault("Admin", Map.of())).doesNotContainKey("secret");
        assertThat(collection.importWarnings).anySatisfy(warning -> {
            assertThat(warning).contains("Admin", "secret");
            assertThat(warning).doesNotContain("sensitive-value");
        });
    }

    private ApiRequest parse(String content) throws Exception {
        Path file = Files.createTempFile(tempDir, "bruno-", ".bru");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new BrunoParser().parse(file.toFile()).requests.get(0);
    }

    private String rawRequestText(ApiRequest request, VariableResolver resolver) throws Exception {
        return new String(new RequestBuilder(null).buildRequest(request, resolver), StandardCharsets.UTF_8);
    }
}
