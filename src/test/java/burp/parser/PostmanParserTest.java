package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class PostmanParserTest {

    private ApiCollection parsePostman(String json) throws Exception {
        File file = File.createTempFile("postman-auth", ".json");
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        try {
            return new PostmanParser().parse(file);
        } finally {
            file.delete();
        }
    }

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

    @Test
    void requestInheritsCollectionBearerAuthWhenRequestAuthMissing() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}"}]},
              "item": [
                {"name": "Get Me", "request": {"method": "GET", "url": "https://api.example.test/me"}}
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(collection.auth).isNotNull();
        assertThat(collection.auth.type).isEqualTo("bearer");
        assertThat(collection.folderAuthModes).isEmpty();
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("bearer");
        assertThat(req.auth.properties.get("token")).isEqualTo("{{accessToken}}");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authExplicitlyDisabled).isFalse();
        assertThat(req.authSource).isEqualTo("collection: Auth Demo");
    }

    @Test
    void nestedFolderAuthOverridesCollectionAuthForChildRequest() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Admin",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{adminToken}}"}]},
                  "item": [
                    {"name": "List Users", "request": {"method": "GET", "url": "https://api.example.test/admin/users"}}
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(collection.folderAuthModes).containsEntry("Admin", "explicit");
        assertThat(collection.folderAuth.get("Admin").type).isEqualTo("bearer");
        assertThat(req.auth.type).isEqualTo("bearer");
        assertThat(req.auth.properties.get("token")).isEqualTo("{{adminToken}}");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authSource).isEqualTo("folder: Admin");
    }

    @Test
    void explicitRequestAuthOverridesInheritedFolderAuth() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Admin",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{adminToken}}"}]},
                  "item": [
                    {
                      "name": "Special",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.test/special",
                        "auth": {"type": "basic", "basic": [{"key": "username", "value": "u"}, {"key": "password", "value": "p"}]}
                      }
                    }
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.auth.type).isEqualTo("basic");
        assertThat(req.auth.properties.get("username")).isEqualTo("u");
        assertThat(req.authOverrideMode).isEqualTo("explicit");
        assertThat(req.explicitAuth).isNotNull();
        assertThat(req.explicitAuth.type).isEqualTo("basic");
        assertThat(req.authInherited).isFalse();
        assertThat(req.authExplicitlyDisabled).isFalse();
        assertThat(req.authSource).isEqualTo("request: Special");
    }

    @Test
    void explicitNoAuthStopsParentInheritance() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Public",
                  "request": {
                    "method": "GET",
                    "url": "https://api.example.test/public",
                    "auth": {"type": "noauth"}
                  }
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("none");
        assertThat(req.hasAuth()).isFalse();
        assertThat(req.authOverrideMode).isEqualTo("none");
        assertThat(req.explicitAuth).isNotNull();
        assertThat(req.explicitAuth.type).isEqualTo("none");
        assertThat(req.authInherited).isFalse();
        assertThat(req.authExplicitlyDisabled).isTrue();
        assertThat(req.authSource).isEqualTo("request: Public");
    }

    @Test
    void folderNoAuthProvenanceIsPreservedForDescendants() throws Exception {
        ApiCollection collection = parsePostman("""
            {
              "info": {"name": "Auth Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{collectionToken}}"}]},
              "item": [
                {
                  "name": "Public",
                  "auth": {"type": "noauth"},
                  "item": [
                    {
                      "name": "Child",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.test/public/child"
                      }
                    }
                  ]
                }
              ]
            }
            """);

        ApiRequest req = collection.requests.get(0);
        assertThat(req.hasAuth()).isFalse();
        assertThat(req.auth).isNotNull();
        assertThat(req.auth.type).isEqualTo("none");
        assertThat(collection.folderAuthModes).containsEntry("Public", "none");
        assertThat(collection.folderAuth.get("Public").type).isEqualTo("none");
        assertThat(req.authOverrideMode).isEqualTo("inherit");
        assertThat(req.authInherited).isTrue();
        assertThat(req.authExplicitlyDisabled).isTrue();
        assertThat(req.authSource).isEqualTo("folder: Public");
    }
}
