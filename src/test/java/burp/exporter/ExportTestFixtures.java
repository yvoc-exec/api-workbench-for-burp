package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ExportTestFixtures {
    private ExportTestFixtures() {
    }

    static ApiCollection sampleCollection() {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.description = "API collection for exports";
        collection.format = "api-workbench";
        collection.version = "1.2.3";
        collection.folderPaths = new ArrayList<>(List.of("Auth", "Auth/OAuth", "Users"));

        collection.variables = new ArrayList<>();
        collection.variables.add(variable("base_url", "https://api.example.test"));
        collection.variables.add(variable("role", "admin"));
        collection.variables.add(variable("client_id", "client-123"));
        collection.variables.add(variable("collection_token", "collection-token"));
        collection.variables.add(variable("", "skip-me"));

        collection.environment = new LinkedHashMap<>();
        collection.environment.put("env_mode", "uat");
        collection.environment.put("", "skip-me");

        collection.auth = auth("basic", Map.of(
                "username", "collection-user",
                "password", "collection-pass"
        ));

        collection.folderAuthModes = new LinkedHashMap<>();
        collection.folderAuthModes.put("Auth", "explicit");
        collection.folderAuth = new LinkedHashMap<>();
        collection.folderAuth.put("Auth", auth("bearer", Map.of("token", "{{token}}")));
        collection.folderVars = new LinkedHashMap<>();
        collection.folderVars.put("Auth", Map.of("folder_var", "folder-value"));
        collection.scriptBlocks = new ArrayList<>();
        collection.scriptBlocks.add(scriptBlock("collection-pre", ScriptDialect.POSTMAN, ScriptPhase.PRE_REQUEST, ScriptScope.COLLECTION, "console.log('collection pre');", 1));
        collection.folderScriptBlocks = new LinkedHashMap<>();
        collection.folderScriptBlocks.put("Auth", new ArrayList<>(List.of(
                scriptBlock("folder-post", ScriptDialect.BRUNO, ScriptPhase.POST_RESPONSE, ScriptScope.FOLDER, "console.log('folder post');", 2)
        )));

        collection.runtimeVars.put("runtime_only", "should-not-export");
        collection.runtimeOAuth2.put("accessToken", "runtime-token");

        collection.requests.add(loginRequest());
        collection.requests.add(oauthRequest());
        collection.requests.add(usersRootRequest());
        collection.requests.add(usersFolderRequest());
        collection.requests.add(graphqlRequest());
        collection.requests.add(uploadRequest());
        return collection;
    }

    static ApiCollection sampleCollectionWithMissingVariable() {
        ApiCollection collection = sampleCollection();
        collection.requests.get(0).body.raw = "{\"username\":\"demo\",\"password\":\"{{missing_password}}\"}";
        return collection;
    }

    static EnvironmentProfile activeEnvironment() {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.id = "env-uat";
        profile.name = "UAT";
        profile.sourceFormat = "api-workbench";
        profile.sourceFileName = "uat.env";
        profile.variables.put("base_url", "https://api.example.test");
        profile.variables.put("role", "admin");
        profile.variables.put("token", "live-token");
        profile.variables.put("client_id", "resolved-client");
        profile.variables.put("user_id", "42");
        profile.variables.put("upload_path", "/tmp/upload.txt");
        profile.variables.put("session_cookie", "abc123");
        profile.variables.put("missing_password", "resolved-password");
        profile.variables.put("", "skip-me");
        profile.oauth2.config.put("accessTokenUrl", "https://auth.example.test/token");
        profile.oauth2.config.put("authorizationUrl", "https://auth.example.test/authorize");
        profile.oauth2.outputBindings.put("accessToken", "env_access_token");
        profile.oauth2.outputBindings.put("refreshToken", "env_refresh_token");
        return profile;
    }

    static EnvironmentProfile activeEnvironmentWithoutMissingPassword() {
        EnvironmentProfile profile = activeEnvironment();
        profile.variables.remove("missing_password");
        return profile;
    }

    static ApiRequest loginRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-login";
        request.name = "Auth";
        request.path = "Auth";
        request.sourceCollection = "APIM";
        request.method = "POST";
        request.url = "{{base_url}}/login";
        request.description = "Login request";
        request.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.editorMaterialized = true;
        request.suppressedAutoHeaders = new LinkedHashSet<>(List.of("accept"));
        request.headers = new ArrayList<>();
        request.headers.add(header("X-Test", "workflow"));
        request.headers.add(header("Accept", "application/json"));
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"username\":\"demo\",\"password\":\"{{missing_password}}\"}";
        request.body.contentType = "application/json";
        request.auth = auth("bearer", Map.of("token", "{{token}}"));
        request.authOverrideMode = "explicit";
        request.authInherited = false;
        request.authExplicitlyDisabled = false;
        request.authSource = "request:Auth";
        request.explicitAuth = auth("bearer", Map.of("token", "{{token}}"));
        request.variables = new ArrayList<>();
        request.variables.add(variable("request_var", "request-value"));
        request.preRequestScripts = new ArrayList<>();
        request.preRequestScripts.add(new ApiRequest.Script("js", "console.log('pre');"));
        request.postResponseScripts = new ArrayList<>();
        request.postResponseScripts.add(new ApiRequest.Script("js", "console.log('post');"));
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock("request-pre", ScriptDialect.POSTMAN, ScriptPhase.PRE_REQUEST, ScriptScope.REQUEST, "pm.environment.set('token', 'abc');", 0));
        request.scriptBlocks.add(scriptBlock("request-post", ScriptDialect.INSOMNIA, ScriptPhase.POST_RESPONSE, ScriptScope.REQUEST, "insomnia.environment.set('token', 'abc');", 1));
        request.sequenceOrder = 1;
        return request;
    }

    static ApiRequest oauthRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-oauth";
        request.name = "OAuth";
        request.path = "Auth/OAuth";
        request.sourceCollection = "APIM";
        request.method = "POST";
        request.url = "{{base_url}}/oauth/token";
        request.description = "OAuth token request";
        request.headers = new ArrayList<>();
        request.headers.add(header("Content-Type", "application/x-www-form-urlencoded"));
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.urlencoded = new ArrayList<>();
        request.body.urlencoded.add(formField("grant_type", "client_credentials"));
        request.body.urlencoded.add(formField("client_id", "{{client_id}}"));
        request.body.urlencoded.add(formField("scope", "read"));
        request.auth = auth("none", Map.of());
        request.authOverrideMode = "none";
        request.authExplicitlyDisabled = true;
        request.authInherited = false;
        request.authSource = "request:OAuth";
        request.sequenceOrder = 2;
        return request;
    }

    static ApiRequest usersRootRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-users-root";
        request.name = "GET /users";
        request.path = "";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "{{base_url}}/users?role={{role}}&page=1";
        request.headers = new ArrayList<>();
        request.headers.add(header("Cookie", "session={{session_cookie}}; theme=dark"));
        request.headers.add(header("X-Request-Id", "abc-123"));
        request.sequenceOrder = 3;
        return request;
    }

    static ApiRequest usersFolderRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-users-folder";
        request.name = "users\\{id}";
        request.path = "Users";
        request.sourceCollection = "APIM";
        request.method = "GET";
        request.url = "{{base_url}}/users/{{user_id}}";
        request.headers = new ArrayList<>();
        request.headers.add(header("Accept", "application/json"));
        request.sequenceOrder = 4;
        return request;
    }

    static ApiRequest graphqlRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-graphql";
        request.name = "GraphQL Query";
        request.path = "Auth";
        request.sourceCollection = "APIM";
        request.method = "POST";
        request.url = "{{base_url}}/graphql";
        request.body = new ApiRequest.Body();
        request.body.mode = "graphql";
        request.body.graphql = new ApiRequest.Body.GraphQL();
        request.body.graphql.query = "query GetUser($id: ID!) { user(id: $id) { name } }";
        request.body.graphql.variables = "{\"id\":\"{{user_id}}\"}";
        request.sequenceOrder = 5;
        return request;
    }

    static ApiRequest uploadRequest() {
        ApiRequest request = new ApiRequest();
        request.id = "req-upload";
        request.name = "Upload";
        request.path = "Users";
        request.sourceCollection = "APIM";
        request.method = "POST";
        request.url = "{{base_url}}/upload";
        request.body = new ApiRequest.Body();
        request.body.mode = "formdata";
        request.body.formdata = new ArrayList<>();
        ApiRequest.Body.FormField meta = formField("meta", "info");
        meta.type = "text";
        request.body.formdata.add(meta);
        ApiRequest.Body.FormField file = formField("file", "");
        file.type = "file";
        file.fileUpload = true;
        file.filePath = "{{upload_path}}";
        request.body.formdata.add(file);
        request.sequenceOrder = 6;
        return request;
    }

    static ApiRequest.Auth auth(String type, Map<String, String> properties) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = type;
        if (properties != null) {
            auth.properties.putAll(properties);
        }
        return auth;
    }

    static ApiRequest.Header header(String key, String value) {
        return new ApiRequest.Header(key, value);
    }

    static ApiRequest.Body.FormField formField(String key, String value) {
        return new ApiRequest.Body.FormField(key, value);
    }

    static ApiRequest.Variable variable(String key, String value) {
        ApiRequest.Variable variable = new ApiRequest.Variable();
        variable.key = key;
        variable.value = value;
        variable.enabled = true;
        return variable;
    }

    static ScriptBlock scriptBlock(String id,
                                   ScriptDialect dialect,
                                   ScriptPhase phase,
                                   ScriptScope scope,
                                   String source,
                                   int order) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = scope;
        block.source = source;
        block.order = order;
        return block;
    }
}
