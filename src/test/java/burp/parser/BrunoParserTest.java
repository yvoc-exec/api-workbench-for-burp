package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoParserTest {

    @Test
    void parseKeepsNestedBracesInBodyAndPostResponseScript() throws Exception {
        Path tempBru = Files.createTempFile(Path.of("target"), "bruno-", ".bru").toAbsolutePath().normalize();
        String bru = """
                meta {
                  name: Nested Body
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.com/items
                  body {
                    {
                      "filters": {
                        "status": "active",
                        "range": {
                          "from": 1,
                          "to": 10
                        }
                      }
                    }
                  }
                }

                script:post-response {
                  if (res.status === 200) {
                    bru.setVar("done", "yes");
                  }
                }
                """;
        Files.writeString(tempBru, bru, StandardCharsets.UTF_8);
        tempBru.toFile().deleteOnExit();

        ApiCollection collection = new BrunoParser().parse(tempBru.toFile());
        ApiRequest req = collection.requests.get(0);

        assertThat(req.body).isNotNull();
        assertThat(req.body.raw).contains("\"filters\"");
        assertThat(req.body.raw).contains("\"range\"");
        assertThat(req.body.raw).contains("\"to\": 10");
        assertThat(req.postResponseScripts)
                .hasSize(1)
                .first()
                .extracting(script -> script.exec)
                .asString()
                .contains("if (responseCode.code === 200) {")
                .contains("pm.environment.set(\"done\", \"yes\");");
    }
}
