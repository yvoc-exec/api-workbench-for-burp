package burp.parser;

import burp.models.ApiCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiImportSafetyTest {
    @TempDir Path tempDir;

    @Test
    void warningsAreOneLineDeduplicatedAndDoNotContainSecretValuesOrAbsolutePaths() throws Exception {
        String canary = "SECRET-CANARY-991";
        Path file = tempDir.resolve("root.yaml");
        Files.writeString(file, """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                x-secret: SECRET-CANARY-991
                paths:
                  /x:
                    post:
                      parameters:
                        - {name: q, in: query, examples: {a: {externalValue: 'SECRET-CANARY-991'}}}
                        - {$ref: 'missing-SECRET-CANARY-991.yaml'}
                      requestBody:
                        content:
                          application/octet-stream: {example: SECRET-CANARY-991}
                      responses: {'200': {description: ok}}
                """);
        ApiCollection collection = new OpenApiParser().parse(file.toFile());
        assertThat(collection.importWarnings).isNotEmpty();
        assertThat(collection.importWarnings).allMatch(w -> !w.contains("\r") && !w.contains("\n")
                && !w.contains(canary) && !w.contains(tempDir.toAbsolutePath().toString()));
        assertThat(collection.importWarnings.stream().distinct().count()).isEqualTo(collection.importWarnings.size());
    }

    @Test
    void oneParserInstanceIsReusableAndSafeForConcurrentCallsAfterFailure() throws Exception {
        OpenApiParser parser = new OpenApiParser();
        Path bad = tempDir.resolve("bad.yaml");
        Files.writeString(bad, "[not: a map]");
        assertThat(parser.canParse(bad.toFile())).isFalse();
        Path one = spec("one.yaml", "One");
        Path two = spec("two.yaml", "Two");
        CompletableFuture<ApiCollection> first = CompletableFuture.supplyAsync(() -> parse(parser, one));
        CompletableFuture<ApiCollection> second = CompletableFuture.supplyAsync(() -> parse(parser, two));
        assertThat(first.get().name).isEqualTo("One");
        assertThat(second.get().name).isEqualTo("Two");
    }

    @Test
    void rejectsYamlBeyondNestingAndAliasLimits() throws Exception {
        Path deep = tempDir.resolve("deep.yaml");
        StringBuilder yaml = new StringBuilder("openapi: 3.0.3\ninfo: {title: T, version: '1'}\npaths:\n");
        for (int i = 0; i < 105; i++) yaml.append("  ".repeat(i + 1)).append("k").append(i).append(":\n");
        yaml.append("  ".repeat(107)).append("value\n");
        Files.writeString(deep, yaml);
        assertThat(new OpenApiParser().canParse(deep.toFile())).isFalse();

        Path aliases = tempDir.resolve("aliases.yaml");
        StringBuilder aliasYaml = new StringBuilder("openapi: 3.0.3\ninfo: &info {title: T, version: '1'}\npaths:\n  /x:\n    get:\n      x-many: [");
        for (int i = 0; i < 55; i++) aliasYaml.append(i == 0 ? "*info" : ", *info");
        aliasYaml.append("]\n      responses: {'200': {description: ok}}\n");
        Files.writeString(aliases, aliasYaml);
        assertThat(new OpenApiParser().canParse(aliases.toFile())).isFalse();
    }

    private Path spec(String name, String title) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "openapi: 3.0.3\ninfo: {title: " + title + ", version: '1'}\npaths: {}\n");
        return file;
    }

    private static ApiCollection parse(OpenApiParser parser, Path file) {
        try { return parser.parse(file.toFile()); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
