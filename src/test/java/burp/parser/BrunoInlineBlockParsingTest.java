package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoInlineBlockParsingTest {

    @TempDir
    Path tempDir;

    @Test
    void sameLineMetaMethodHeadersBearerAuthAndTextBodyAreImported() throws Exception {
        String source = """
                meta { name: Inline Request }
                post { url: https://api.example.test/inline }
                headers { X-Inline: yes }
                auth:bearer { token: inline-token }
                body:text { inline { body } value }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "headers",
                "auth:bearer",
                "body:text"
        );

        ApiCollection collection = parseSingleFile(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.name).isEqualTo("Inline Request");
        assertThat(request.method).isEqualTo("POST");
        assertThat(request.url).isEqualTo("https://api.example.test/inline");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-Inline");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "inline-token");
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("inline { body } value");
        assertThat(request.body.contentType).isEqualTo("text/plain");

        String raw = rawRequestText(request);
        assertThat(raw).contains("POST /inline HTTP/1.1");
        assertThat(raw).contains("X-Inline: yes");
        assertThat(raw).contains("Authorization: Bearer inline-token");
        assertThat(raw).contains("Content-Type: text/plain");
        assertThat(raw).contains("inline { body } value");
    }

    @Test
    void sameLineMethodBlockFollowedByLaterHeadersStaysImportable() throws Exception {
        String source = """
                meta { name: Later Headers }
                get { url: https://api.example.test/inline-later }
                headers {
                  X-After: yes
                }
                """;

        ApiCollection collection = parseSingleFile(source);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.method).isEqualTo("GET");
        assertThat(request.url).isEqualTo("https://api.example.test/inline-later");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });

        String raw = rawRequestText(request);
        assertThat(raw).contains("GET /inline-later HTTP/1.1");
        assertThat(raw).contains("X-After: yes");
    }

    @Test
    void inlineNoAuthBlockBlocksCollectionAuthInheritance() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("inline-no-auth"));
        Files.writeString(root.resolve("Collection.bru"), """
                auth:bearer {
                  token: collection-token
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Request.bru"), """
                meta { name: Inline No Auth }
                get { url: https://api.example.test/public }
                auth:none {}
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new BrunoParser().parse(root.toFile());
        AuthInheritanceResolver.recomputeCollectionAuth(collection);

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.auth).isNotNull();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(request.hasAuth()).isFalse();
        assertThat(rawRequestText(request)).doesNotContain("Authorization:");
    }

    @Test
    void inlineBodyFollowedByMultilineScriptsAndHeadersKeepsLaterBlocksVisible() throws Exception {
        String source = """
                meta { name: Inline Body With Siblings }
                post { url: https://api.example.test/body-followed-by-siblings }
                body:text { inline body }
                script:pre-request {
                  bru.setVar('after', 'ok');
                }
                headers {
                  X-After: yes
                }
                script:post-response {
                  bru.setVar('post', 'ok');
                }
                """;

        assertThat(BrunoBlockScanner.scan(source)).extracting(block -> block.name).containsExactly(
                "meta",
                "post",
                "body:text",
                "script:pre-request",
                "headers",
                "script:post-response"
        );

        ApiCollection collection = parseSingleFile(source);
        assertThat(collection.requests).hasSize(1);
        assertThat(collection.importedRequestCount).isEqualTo(1);
        assertThat(collection.skippedRequestCount).isEqualTo(0);
        assertThat(collection.importWarnings).isNullOrEmpty();

        ApiRequest request = collection.requests.get(0);
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).isEqualTo("inline body");
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('after', 'ok');");
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('post', 'ok');");
        assertThat(request.scriptBlocks).hasSize(2);

        String raw = rawRequestText(request);
        assertThat(raw).contains("X-After: yes");
        assertThat(raw).contains("inline body");
    }

    private ApiCollection parseSingleFile(String source) throws Exception {
        Path file = Files.createTempFile(tempDir, "bruno-", ".bru");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return new BrunoParser().parse(file.toFile());
    }

    private String rawRequestText(ApiRequest request) throws Exception {
        return new String(new RequestBuilder(null).buildRequest(request, new VariableResolver()), StandardCharsets.UTF_8);
    }
}
