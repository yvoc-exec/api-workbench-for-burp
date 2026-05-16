package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class PostmanParserTest {

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
}
