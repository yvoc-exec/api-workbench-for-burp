package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.utils.RequestBuilder;
import burp.parser.VariableResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoBlockScannerTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @MethodSource("textBodyCases")
    void structuralTextBodiesRemainIntactAndKeepLaterBlocksVisible(String source, String expectedBodyRaw) throws Exception {
        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:text",
                "headers",
                "script:pre-request"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo(expectedBodyRaw);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('after', 'ok');");
        assertThat(request.scriptBlocks).singleElement().satisfies(block -> {
            assertThat(block.dialect).isEqualTo(ScriptDialect.BRUNO);
            assertThat(block.phase).isEqualTo(ScriptPhase.PRE_REQUEST);
            assertThat(block.source).contains("bru.setVar('after', 'ok');");
        });
    }

    @Test
    void nestedJsonWithIndentedStandaloneBracesClosesOnlyAtStructuralOuterDelimiter() throws Exception {
        String source = """
                meta {
                  name: Nested JSON Safety
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/nested-json
                }

                body:json {
                  {
                    "outer": {
                      "inner": {
                        "value": 1
                      }
                    },
                    "list": [
                      {
                        "name": "first"
                      },
                      {
                        "name": "second"
                      }
                    ]
                  }
                }

                headers {
                  X-After: yes
                }

                script:post-response {
                  bru.setVar('after', 'ok');
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:json",
                "headers",
                "script:post-response"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).contains("\"outer\": {");
        assertThat(request.body.raw).contains("\"name\": \"second\"");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('after', 'ok');");
    }

    @Test
    void preAndPostResponseScriptsKeepOpaqueBrunoTextAndFollowingBlocksVisible() throws Exception {
        String source = """
                meta {
                  name: Script Safety
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/script-safety
                }

                script:pre-request {
                  {
                    const value = `${format("}")}`;
                  }
                  {
                    const value = `${(() => {
                      return "}";
                    })()}`;
                  }
                  function matcher() {
                    return /}/;
                  }
                  {
                    const value = `${(() => {
                      /* } */
                      return /}/;
                    })()}`;
                  }
                }

                script:post-response {
                  const value = `${(() => {
                    /* } */
                    return /}/;
                  })()}`;
                }

                headers {
                  X-After: yes
                }

                body:text {
                  trailing body
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "script:pre-request",
                "script:post-response",
                "headers",
                "body:text"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("const value = `${format(\"}\")}`;")
                .contains("const value = `${(() => {")
                .contains("function matcher() {")
                .contains("return /}/;");
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("const value = `${(() => {")
                .contains("/* } */")
                .contains("return /}/;");
        assertThat(request.scriptBlocks)
                .hasSize(2)
                .allSatisfy(block -> assertThat(block.dialect).isEqualTo(ScriptDialect.BRUNO));
        assertThat(request.scriptBlocks)
                .extracting(block -> block.phase)
                .containsExactly(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("trailing body");
    }

    @Test
    void bodyTextWithUnindentedBraceLineRemainsLiteralAndKeepsLaterHeadersVisible() throws Exception {
        String source = """
                meta {
                name: Body Text Brace Recovery
                type: http
                seq: 1
                }

                post {
                url: https://api.example.test/body-text-brace
                }

                body:text {
                X-Pattern: literal {
                }

                headers {
                X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:text",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("X-Pattern: literal {");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(rawRequestText(request)).contains("X-After: yes");
        assertThat(rawRequestText(request)).contains("X-Pattern: literal {");
    }

    @Test
    void scriptPreRequestWithUnindentedFunctionLineRemainsOpaqueAndKeepsLaterHeadersVisible() throws Exception {
        String source = """
                meta {
                name: Script Brace Recovery
                type: http
                seq: 1
                }

                post {
                url: https://api.example.test/script-brace
                }

                script:pre-request {
                function run() {
                  return /}/;
                }
                }

                headers {
                X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "script:pre-request",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .isEqualTo("""
                        function run() {
                          return /}/;
                        }
                        """.trim());
        assertThat(request.scriptBlocks).singleElement().satisfies(block -> {
            assertThat(block.phase).isEqualTo(ScriptPhase.PRE_REQUEST);
            assertThat(block.source).contains("function run() {");
        });
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(rawRequestText(request)).doesNotContain("function run() {");
        assertThat(rawRequestText(request)).doesNotContain("return /}/;");
        assertThat(rawRequestText(request)).contains("X-After: yes");
    }

    @Test
    void graphqlBodyWithUnindentedQueryLineRemainsOpaqueAndKeepsLaterHeadersVisible() throws Exception {
        String source = """
                meta {
                name: GraphQL Brace Recovery
                type: http
                seq: 1
                }

                post {
                url: https://api.example.test/graphql-brace
                }

                body:graphql {
                query GetUser {
                  user {
                    id
                  }
                }
                }

                headers {
                X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:graphql",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("graphql");
        assertThat(request.body.graphql).isNotNull();
        assertThat(request.body.graphql.query).isEqualTo("""
                query GetUser {
                  user {
                    id
                  }
                }
                """.trim());
        assertThat(request.body.graphql.variables).isEqualTo("{}");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(rawRequestText(request)).contains("query GetUser");
        assertThat(rawRequestText(request)).contains("X-After: yes");
    }

    @ParameterizedTest
    @MethodSource("headerValueCases")
    void multilineHeaderDictionaryValuesStayImported(String source, String expectedHeaderKey, String expectedHeaderValue) throws Exception {
        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).contains("headers", "script:post-response");

        ApiRequest request = collection.requests.get(0);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo(expectedHeaderKey);
            assertThat(header.value).isEqualTo(expectedHeaderValue);
        });
        assertThat(rawRequestText(request)).contains(expectedHeaderKey + ": " + expectedHeaderValue);
    }

    @Test
    void bearerTokenValueWithApostropheStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Bearer Token Quotes
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/auth-quote
                }

                auth:bearer {
                  token: It's working
                }

                headers {
                  X-After: yes
                }
                """;

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "It's working");
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));
        assertThat(rawRequestText(request)).contains("Authorization: Bearer It's working");
    }

    @Test
    void varsValueWithDoubleQuoteStaysImportableAndResolvable() throws Exception {
        String source = """
                meta {
                  name: Vars Quotes
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/vars
                }

                vars {
                  baseUrl: He said "hello
                }

                headers {
                  X-Resolved: {{baseUrl}}
                  X-After: yes
                }
                """;

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.variables).singleElement().satisfies(variable -> {
            assertThat(variable.key).isEqualTo("baseUrl");
            assertThat(variable.value).isEqualTo("He said \"hello");
        });
        assertThat(request.headers).extracting(header -> header.key).containsExactly("X-Resolved", "X-After");

        burp.parser.VariableResolver resolver = new burp.parser.VariableResolver();
        resolver.addCustomVariable("baseUrl", "He said \"hello");
        assertThat(rawRequestText(request, resolver)).contains("X-Resolved: He said \"hello");
    }

    @Test
    void urlencodedBodyValueWithBacktickStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Urlencoded Quotes
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/form
                }

                body:form-urlencoded {
                  note: `literal
                  message: It's working
                }

                headers {
                  X-After: yes
                }
                """;

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("urlencoded");
        assertThat(request.body.urlencoded).extracting(field -> field.key + "=" + field.value)
                .containsExactly("note=`literal", "message=It's working");
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));

        String raw = rawRequestText(request);
        assertThat(raw).contains("note=%60literal");
        assertThat(raw).contains("message=It%27s+working");
    }

    @Test
    void multipartBodyValueWithQuotesStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Multipart Quotes
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/upload
                }

                body:multipart-form {
                  description: It's working
                  note: He said "hello
                }

                headers {
                  X-After: yes
                }
                """;

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("formdata");
        assertThat(request.body.formdata).extracting(field -> field.key + "=" + field.value)
                .containsExactly("description=It's working", "note=He said \"hello");
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));

        String raw = rawRequestText(request);
        assertThat(raw).contains("name=\"description\"");
        assertThat(raw).contains("It's working");
        assertThat(raw).contains("He said \"hello");
    }

    @Test
    void headersValueEndingWithOpenBraceStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Header Brace Value
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/header-brace
                }

                headers {
                  X-Pattern: literal {
                }

                body:text {
                  request survived
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "headers",
                "body:text"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Pattern");
            assertThat(header.value).isEqualTo("literal {");
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("request survived");
        assertThat(rawRequestText(request)).contains("X-Pattern: literal {");
        assertThat(rawRequestText(request)).contains("request survived");
    }

    @Test
    void varsValueEndingWithOpenBraceStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Vars Brace Value
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/vars-brace
                }

                vars {
                  prefix: {
                }

                headers {
                  X-Resolved: {{prefix}}
                }

                body:text {
                  request survived
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "vars",
                "headers",
                "body:text"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.variables).singleElement().satisfies(variable -> {
            assertThat(variable.key).isEqualTo("prefix");
            assertThat(variable.value).isEqualTo("{");
        });
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Resolved");
            assertThat(header.value).isEqualTo("{{prefix}}");
        });

        VariableResolver resolver = new VariableResolver();
        resolver.addCustomVariable("prefix", "{");
        assertThat(rawRequestText(request, resolver)).contains("X-Resolved: {");
        assertThat(rawRequestText(request, resolver)).contains("request survived");
    }

    @Test
    void bearerAuthValueEndingWithOpenBraceStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Bearer Brace Value
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/auth-brace
                }

                auth:bearer {
                  token: abc{
                }

                headers {
                  X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "auth:bearer",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "abc{");
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));
        assertThat(rawRequestText(request)).contains("Authorization: Bearer abc{");
    }

    @Test
    void urlencodedBodyValueEndingWithOpenBraceStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Urlencoded Brace Value
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/form-brace
                }

                body:form-urlencoded {
                  pattern: literal {
                }

                headers {
                  X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:form-urlencoded",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("urlencoded");
        assertThat(request.body.urlencoded).singleElement().satisfies(field -> {
            assertThat(field.key).isEqualTo("pattern");
            assertThat(field.value).isEqualTo("literal {");
        });
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));
        assertThat(rawRequestText(request)).contains("pattern=literal+%7B");
    }

    @Test
    void multipartBodyValueEndingWithOpenBraceStaysImportable() throws Exception {
        String source = """
                meta {
                  name: Multipart Brace Value
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/upload-brace
                }

                body:multipart-form {
                  description: literal {
                }

                headers {
                  X-After: yes
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:multipart-form",
                "headers"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("formdata");
        assertThat(request.body.formdata).singleElement().satisfies(field -> {
            assertThat(field.key).isEqualTo("description");
            assertThat(field.value).isEqualTo("literal {");
        });
        assertThat(request.headers).singleElement().satisfies(header -> assertThat(header.key).isEqualTo("X-After"));
        assertThat(rawRequestText(request)).contains("literal {");
    }

    @Test
    void sameLineMethodAndBraceValueBlocksSurviveMalformedSiblingFiltering() throws Exception {
        String source = """
                meta {
                  name: Brace Value
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/patterns
                }

                headers {
                  X-Pattern: literal {
                }

                body:text {
                  request survived
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "headers",
                "body:text"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.method).isEqualTo("GET");
        assertThat(request.url).isEqualTo("https://api.example.test/patterns");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Pattern");
            assertThat(header.value).isEqualTo("literal {");
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("request survived");
    }

    @Test
    void sameLineMethodBlockIsNotRemovedWhenLaterDictionaryLinesContainQuotes() throws Exception {
        String source = """
                meta {
                  name: Messages
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/messages
                }

                headers {
                  X-Message: It's working
                }

                body:text {
                  request survived
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "headers",
                "body:text"
        );

        ApiCollection collection = parseCollection(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.method).isEqualTo("GET");
        assertThat(request.url).isEqualTo("https://api.example.test/messages");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Message");
            assertThat(header.value).isEqualTo("It's working");
        });
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("request survived");
        assertThat(rawRequestText(request)).contains("GET /messages HTTP/1.1");
    }

    private static Stream<Arguments> textBodyCases() {
        return Stream.of(
                Arguments.of("""
                        meta {
                          name: Text Body One
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/text-one
                        }

                        body:text {
                          It's working correctly.
                        }

                        headers {
                          X-After: yes
                        }

                        script:pre-request {
                          bru.setVar('after', 'ok');
                        }
                        """, "It's working correctly."),
                Arguments.of("""
                        meta {
                          name: Text Body Two
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/text-two
                        }

                        body:text {
                          literal } character remains in the body
                        }

                        headers {
                          X-After: yes
                        }

                        script:pre-request {
                          bru.setVar('after', 'ok');
                        }
                        """, "literal } character remains in the body"),
                Arguments.of("""
                        meta {
                          name: Text Body Three
                          type: http
                          seq: 1
                        }

                        post {
                          url: https://api.example.test/text-three
                        }

                        body:text {
                          const example = `not JavaScript parsing`;
                          // this is just literal body text
                        }

                        headers {
                          X-After: yes
                        }

                        script:pre-request {
                          bru.setVar('after', 'ok');
                        }
                        """, """
                        const example = `not JavaScript parsing`;
                          // this is just literal body text
                        """.stripTrailing())
        );
    }

    private static Stream<Arguments> headerValueCases() {
        return Stream.of(
                Arguments.of("""
                        meta {
                          name: Header Apostrophe
                          type: http
                          seq: 1
                        }

                        get {
                          url: https://api.example.test/header-apostrophe
                        }

                        headers {
                          X-Message: It's working
                        }

                        script:post-response {
                          bru.setVar('after', 'ok');
                        }
                """, "X-Message", "It's working"),
                Arguments.of("""
                        meta {
                          name: Header Quote
                          type: http
                          seq: 1
                        }

                        get {
                          url: https://api.example.test/header-quote
                        }

                        headers {
                          X-Message: He said "hello
                        }

                        script:post-response {
                          bru.setVar('after', 'ok');
                        }
                        """, "X-Message", "He said \"hello"),
                Arguments.of("""
                        meta {
                          name: Header Backtick
                          type: http
                          seq: 1
                        }

                        get {
                          url: https://api.example.test/header-backtick
                        }

                        headers {
                          X-Code: `literal
                        }

                        script:post-response {
                          bru.setVar('after', 'ok');
                        }
                        """, "X-Code", "`literal")
        );
    }

    private ApiCollection parseCollection(String content) throws Exception {
        Path file = Files.createTempFile(tempDir, "bruno-", ".bru");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new BrunoParser().parse(file.toFile());
    }

    private String rawRequestText(ApiRequest request) throws Exception {
        return rawRequestText(request, new VariableResolver());
    }

    private String rawRequestText(ApiRequest request, VariableResolver resolver) throws Exception {
        return new String(new RequestBuilder(null).buildRequest(request, resolver), StandardCharsets.UTF_8);
    }
}
