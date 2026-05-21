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
    void preservesDisabledBodyParams() throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "insomnia-disabled-params-", ".json");
        Files.writeString(file, """
                {
                  "__type": "export",
                  "__export_format": "Insomnia v4",
                  "resources": [
                    {
                      "_type": "request",
                      "_id": "req_urlencoded",
                      "name": "Submit Form",
                      "method": "POST",
                      "url": "https://example.test/form",
                      "body": {
                        "mimeType": "application/x-www-form-urlencoded",
                        "params": [
                          {"name": "enabled", "value": "yes"},
                          {"name": "disabled", "value": "no", "disabled": true}
                        ]
                      }
                    },
                    {
                      "_type": "request",
                      "_id": "req_multipart",
                      "name": "Upload Form",
                      "method": "POST",
                      "url": "https://example.test/upload",
                      "body": {
                        "mimeType": "multipart/form-data",
                        "params": [
                          {"name": "file_enabled", "value": "yes"},
                          {"name": "file_disabled", "value": "no", "disabled": true}
                        ]
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new InsomniaParser().parse(file.toFile());

        ApiRequest urlencoded = collection.requests.stream()
                .filter(r -> "Submit Form".equals(r.name))
                .findFirst()
                .orElseThrow();
        ApiRequest multipart = collection.requests.stream()
                .filter(r -> "Upload Form".equals(r.name))
                .findFirst()
                .orElseThrow();

        assertThat(urlencoded.body.urlencoded).hasSize(2);
        assertThat(urlencoded.body.urlencoded.get(0).disabled).isFalse();
        assertThat(urlencoded.body.urlencoded.get(1).key).isEqualTo("disabled");
        assertThat(urlencoded.body.urlencoded.get(1).disabled).isTrue();

        assertThat(multipart.body.formdata).hasSize(2);
        assertThat(multipart.body.formdata.get(0).disabled).isFalse();
        assertThat(multipart.body.formdata.get(1).key).isEqualTo("file_disabled");
        assertThat(multipart.body.formdata.get(1).disabled).isTrue();
    }

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
