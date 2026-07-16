package burp.exporter;

import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoCollectionExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesBrunoZipWithCollectionFoldersRequestsAndMetadata() throws Exception {
        ApiCollection collection = ExportTestFixtures.sampleCollection();
        EnvironmentProfile activeEnvironment = ExportTestFixtures.activeEnvironment();
        Path output = tempDir.resolve("apim.bruno.zip");

        new CollectionExportService().exportCollection(
                collection,
                new CollectionExportOptions(CollectionExportFormat.BRUNO_ZIP, output, true, activeEnvironment, Map.of())
        );

        Map<String, String> entries = readZipEntries(output);
        assertThat(entries.keySet()).contains(
                "APIM/bruno.json",
                "APIM/_collection.bru",
                "APIM/Auth/Auth.bru",
                "APIM/Auth/OAuth/OAuth.bru",
                "APIM/Users/users_{id}.bru"
        );

        String loginBru = entries.get("APIM/Auth/Auth.bru");
        assertThat(loginBru).contains("meta {");
        assertThat(loginBru).contains("post {");
        assertThat(loginBru).contains("headers {");
        assertThat(loginBru).contains("body:json {");
        assertThat(loginBru).contains("auth {");
        assertThat(loginBru).contains("script:pre-request {");
        assertThat(loginBru).contains("script:post-response {");
        assertThat(loginBru).contains("resolved-password");

        String collectionVars = entries.get("APIM/_collection.bru");
        assertThat(collectionVars).contains("vars {");
        assertThat(collectionVars).contains("base_url: \"https://api.example.test\"");
    }

    private static Map<String, String> readZipEntries(Path output) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(output))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                int read;
                while ((read = zin.read(buffer)) >= 0) {
                    sb.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                }
                entries.put(entry.getName(), sb.toString());
            }
        }
        return entries;
    }
}
