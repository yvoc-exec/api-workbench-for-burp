package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
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

    private ApiCollection parseCollection(String content) throws Exception {
        Path file = Files.createTempFile(tempDir, "bruno-", ".bru");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new BrunoParser().parse(file.toFile());
    }
}
