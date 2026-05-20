package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InsomniaParserTest {

    @Test
    void requestGroupAuthIsStoredAsFolderMetadataAndInheritedByChildRequests() throws Exception {
        ApiCollection collection = parseInsomniaJson("""
                {
                  "__type": "export",
                  "__export_format": "4",
                  "resources": [
                    {"_id": "wrk_1", "_type": "workspace", "name": "Demo"},
                    {
                      "_id": "fld_admin",
                      "_type": "request_group",
                      "parentId": "wrk_1",
                      "name": "Admin",
                      "authentication": {"type": "bearer", "token": "{{adminToken}}"}
                    },
                    {
                      "_id": "req_users",
                      "_type": "request",
                      "parentId": "fld_admin",
                      "name": "Users",
                      "method": "GET",
                      "url": "https://api.example.test/users"
                    }
                  ]
                }
                """);

        ApiRequest request = collection.requests.get(0);
        assertThat(collection.folderAuthModes).containsEntry("Admin", "explicit");
        assertThat(collection.folderAuth.get("Admin").type).isEqualTo("bearer");
        assertThat(request.auth.type).isEqualTo("bearer");
        assertThat(request.auth.properties).containsEntry("token", "{{adminToken}}");
        assertThat(request.authInherited).isTrue();
        assertThat(request.authSource).isEqualTo("folder: Admin");
    }

    @Test
    void requestNoAuthStopsInsomniaFolderInheritance() throws Exception {
        ApiCollection collection = parseInsomniaJson("""
                {
                  "__type": "export",
                  "__export_format": "4",
                  "resources": [
                    {"_id": "wrk_1", "_type": "workspace", "name": "Demo"},
                    {
                      "_id": "fld_admin",
                      "_type": "request_group",
                      "parentId": "wrk_1",
                      "name": "Admin",
                      "authentication": {"type": "bearer", "token": "{{adminToken}}"}
                    },
                    {
                      "_id": "req_public",
                      "_type": "request",
                      "parentId": "fld_admin",
                      "name": "Public",
                      "method": "GET",
                      "url": "https://api.example.test/public",
                      "authentication": {"type": "none"}
                    }
                  ]
                }
                """);

        ApiRequest request = collection.requests.get(0);
        assertThat(request.hasAuth()).isFalse();
        assertThat(request.auth.type).isEqualTo("none");
        assertThat(request.authOverrideMode).isEqualTo("none");
        assertThat(request.authExplicitlyDisabled).isTrue();
        assertThat(request.authInherited).isFalse();
        assertThat(request.authSource).isEqualTo("request: Public");
    }

    private ApiCollection parseInsomniaJson(String json) throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "insomnia-auth-", ".json").toAbsolutePath().normalize();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        return new InsomniaParser().parse(file.toFile());
    }
}
