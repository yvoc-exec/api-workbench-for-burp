package burp.parser;

import burp.models.ApiCollection;
import burp.scripts.ScriptBlock;
import burp.scripts.capabilities.ScriptTrustReviewModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserRegistryScriptTrustTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearHandler() {
        ParserRegistry.clearScriptTrustReviewHandler();
    }

    @Test
    void importedScriptsStayDisabledWhenNoReviewerIsInstalled() throws Exception {
        Path collectionFile = writePostmanCollection();
        CollectionParser parser = new ParserRegistry().detectParser(collectionFile.toFile());

        assertThat(parser).isInstanceOf(PostmanParser.class);
        ApiCollection collection = parser.parse(collectionFile.toFile());

        assertThat(collection.requests).hasSize(1);
        assertThat(collection.requests.get(0).scriptBlocks).singleElement().satisfies(block -> {
            assertThat(block.enabled).isFalse();
            assertThat(block.metadata).containsEntry("trustState", "disabled");
        });
    }

    @Test
    void reviewerCanTrustSelectedSupportedScripts() throws Exception {
        Path collectionFile = writePostmanCollection();
        ParserRegistry.setScriptTrustReviewHandler(model -> {
            for (var item : model.items()) {
                model.setSelectedForTrust(item.blockId, true);
            }
            return ScriptTrustReviewModel.Decision.TRUST_SELECTED;
        });
        CollectionParser parser = new ParserRegistry().detectParser(collectionFile.toFile());

        ApiCollection collection = parser.parse(collectionFile.toFile());

        ScriptBlock block = collection.requests.get(0).scriptBlocks.get(0);
        assertThat(block.enabled).isTrue();
        assertThat(block.metadata).containsEntry("trustState", "trusted");
    }

    @Test
    void cancellationAbortsBeforeCollectionLeavesParserRegistry() throws Exception {
        Path collectionFile = writePostmanCollection();
        ParserRegistry.setScriptTrustReviewHandler(model -> ScriptTrustReviewModel.Decision.CANCEL_IMPORT);
        CollectionParser parser = new ParserRegistry().detectParser(collectionFile.toFile());

        assertThatThrownBy(() -> parser.parse(collectionFile.toFile()))
                .isInstanceOf(ParserRegistry.ScriptImportCancelledException.class)
                .hasMessageContaining("cancelled");
    }

    private Path writePostmanCollection() throws Exception {
        String json = """
                {
                  "info": {
                    "name": "Trust Fixture",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "Get User",
                      "event": [
                        {
                          "listen": "prerequest",
                          "script": {
                            "type": "text/javascript",
                            "exec": ["pm.environment.set('tenant', 'blue');"]
                          }
                        }
                      ],
                      "request": {
                        "method": "GET",
                        "url": {
                          "raw": "https://example.invalid/users/1",
                          "protocol": "https",
                          "host": ["example", "invalid"],
                          "path": ["users", "1"]
                        }
                      }
                    }
                  ]
                }
                """;
        Path file = tempDir.resolve("trust-fixture.postman_collection.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
