package burp.parser;

import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoBlockScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void preRequestScriptWithBracesCommentsRegexAndTemplateLiteralKeepsFollowingBlocksVisible() throws Exception {
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
                  const value = `literal } ${other}`;
                  const regex = /}/;
                  // comment containing }
                  /* block comment containing } */
                  if (true) {
                    bru.setVar('nested', 'yes');
                  }
                }

                script:post-response {
                  bru.setVar('after', 'ok');
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
                "script:post-response",
                "headers"
        );

        Path bru = tempDir.resolve("script-safety.bru");
        Files.writeString(bru, source, StandardCharsets.UTF_8);

        ApiRequest request = new BrunoParser().parse(bru.toFile()).requests.get(0);
        assertThat(request.preRequestScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("const value = `literal } ${other}`;")
                .contains("const regex = /}/;")
                .contains("// comment containing }")
                .contains("/* block comment containing } */")
                .contains("bru.setVar('nested', 'yes');");
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("bru.setVar('after', 'ok');");
        assertThat(request.scriptBlocks)
                .extracting(block -> block.dialect)
                .containsExactly(ScriptDialect.BRUNO, ScriptDialect.BRUNO);
        assertThat(request.scriptBlocks)
                .extracting(block -> block.phase)
                .containsExactly(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE);
        assertThat(request.headers).singleElement().satisfies(header -> {
            assertThat(header.key).isEqualTo("X-After");
            assertThat(header.value).isEqualTo("yes");
        });
    }

    @Test
    void postResponseScriptWithNestedBracesDoesNotHideFollowingBodyBlock() throws Exception {
        String source = """
                meta {
                  name: Post Script Safety
                  type: http
                  seq: 1
                }

                get {
                  url: https://api.example.test/post-script-safety
                }

                script:post-response {
                  const value = `literal } ${other}`;
                  const regex = /}/;
                  // comment containing }
                  /* block comment containing } */
                  if (true) {
                    bru.setVar('nested', 'yes');
                  }
                }

                body:text {
                  visible body text
                }
                """;

        List<BrunoBlockScanner.Block> blocks = BrunoBlockScanner.scan(source);
        assertThat(blocks).extracting(block -> block.name).containsExactly(
                "meta",
                "get",
                "script:post-response",
                "body:text"
        );

        Path bru = tempDir.resolve("post-script-safety.bru");
        Files.writeString(bru, source, StandardCharsets.UTF_8);

        ApiRequest request = new BrunoParser().parse(bru.toFile()).requests.get(0);
        assertThat(request.postResponseScripts).singleElement().extracting(script -> script.exec)
                .asString()
                .contains("const value = `literal } ${other}`;")
                .contains("const regex = /}/;")
                .contains("// comment containing }")
                .contains("/* block comment containing } */")
                .contains("bru.setVar('nested', 'yes');");
        assertThat(request.body).isNotNull();
        assertThat(request.body.mode).isEqualTo("raw");
        assertThat(request.body.raw).contains("visible body text");
    }
}
