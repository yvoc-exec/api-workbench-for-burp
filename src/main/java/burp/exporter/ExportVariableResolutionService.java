package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.UnresolvedVariableIssue;
import burp.parser.VariableResolver;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestPathResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ExportVariableResolutionService {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|([^}]+))?\\}\\}");

    private ExportVariableResolutionService() {
    }

    public static VariableResolver buildResolver(ApiCollection collection,
                                                  ApiRequest request,
                                                  EnvironmentProfile activeEnvironment,
                                                  Map<String, String> exportOnlyVariables) {
        VariableResolver resolver = new VariableResolver();
        if (collection != null && collection.environment != null) {
            resolver.addAll(collection.environment);
        }
        if (collection != null && collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    resolver.addCustomVariable(variable.key, variable.value);
                }
            }
        }
        if (collection != null && request != null && collection.folderVars != null) {
            for (String ancestor : RequestPathResolver.getAncestorFolderPaths(collection, request)) {
                Map<String, String> folder = collection.folderVars.get(ancestor);
                if (folder != null) resolver.addAll(folder);
            }
        }
        if (request != null && request.variables != null) {
            for (ApiRequest.Variable variable : request.variables) {
                if (variable != null && variable.enabled && variable.key != null && variable.value != null) {
                    resolver.addCustomVariable(variable.key, variable.value);
                }
            }
        }
        if (activeEnvironment != null && activeEnvironment.variables != null) {
            resolver.addAll(activeEnvironment.variables);
        }
        if (exportOnlyVariables != null && !exportOnlyVariables.isEmpty()) {
            resolver.addAll(exportOnlyVariables);
        }
        return resolver;
    }

    static void addDuplicateEnabledVariableWarnings(ApiCollection collection,
                                                     ApiRequest request,
                                                     List<String> warnings) {
        warnDuplicates(collection != null ? collection.variables : null, "collection", warnings);
        warnDuplicates(request != null ? request.variables : null,
                request != null ? "request '" + ExportWarningSupport.label(request.name) + "'" : "request",
                warnings);
    }

    private static void warnDuplicates(List<ApiRequest.Variable> variables,
                                       String scope,
                                       List<String> warnings) {
        Set<String> enabled = new java.util.LinkedHashSet<>();
        if (variables == null) return;
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || !variable.enabled || variable.key == null) continue;
            if (!enabled.add(variable.key)) {
                ExportWarningSupport.add(warnings, "Export resolution collapsed duplicate enabled variable '"
                        + ExportWarningSupport.label(variable.key) + "' in " + scope
                        + " using the last occurrence.");
            }
        }
    }

    public static List<UnresolvedVariableIssue> collectUnresolvedIssues(ApiCollection collection,
                                                                        EnvironmentProfile activeEnvironment) {
        return collectUnresolvedIssues(collection, activeEnvironment, Map.of());
    }

    public static List<UnresolvedVariableIssue> collectUnresolvedIssues(ApiCollection collection,
                                                                        EnvironmentProfile activeEnvironment,
                                                                        Map<String, String> exportOnlyVariables) {
        return collectUnresolvedIssues(collection, activeEnvironment, exportOnlyVariables, null);
    }

    public static List<UnresolvedVariableIssue> collectUnresolvedIssues(ApiCollection collection,
                                                                        EnvironmentProfile activeEnvironment,
                                                                        Map<String, String> exportOnlyVariables,
                                                                        CollectionExportFormat format) {
        if (format == CollectionExportFormat.BRUNO_ZIP || format == CollectionExportFormat.INSOMNIA_JSON) {
            return collectFromSerializedTarget(collection, activeEnvironment, exportOnlyVariables, format);
        }
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        if (collection == null || collection.requests == null) {
            return issues;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            VariableResolver resolver = buildResolver(collection, request, activeEnvironment, exportOnlyVariables);
            scanRequestValues(issues, collection, request, resolver, format);
        }
        scanCollectionLevelValues(issues, collection,
                buildResolver(collection, null, activeEnvironment, exportOnlyVariables), format);
        return dedupe(issues);
    }

    /**
     * Reads the completed target artifact and reports templates that remain in
     * fields active under that target's own representation rules.
     */
    public static List<UnresolvedVariableIssue> collectUnresolvedIssuesFromArtifact(
            Path artifact, CollectionExportFormat format, ApiCollection collection) throws IOException {
        if (artifact == null || format == null) return List.of();
        String collectionName = collection != null && collection.name != null ? collection.name : "";
        return switch (format) {
            case BRUNO_ZIP -> scanBrunoZip(Files.readAllBytes(artifact), collection, collectionName);
            case INSOMNIA_JSON -> scanInsomniaRoot(
                    JsonParser.parseString(Files.readString(artifact, StandardCharsets.UTF_8)), collectionName);
            default -> List.of();
        };
    }

    private static List<UnresolvedVariableIssue> collectFromSerializedTarget(
            ApiCollection collection, EnvironmentProfile activeEnvironment,
            Map<String, String> exportOnlyVariables, CollectionExportFormat format) {
        CollectionExportOptions options = new CollectionExportOptions(
                format, Path.of("target-exact-diagnostics"), true,
                activeEnvironment, exportOnlyVariables);
        List<String> ignoredWarnings = new ArrayList<>();
        try {
            if (format == CollectionExportFormat.BRUNO_ZIP) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BrunoCollectionExporter.write(collection, options, bytes, ignoredWarnings);
                return scanBrunoZip(bytes.toByteArray(), collection,
                        collection != null && collection.name != null ? collection.name : "");
            }
            JsonObject root = InsomniaCollectionExporter.build(collection, options, ignoredWarnings);
            return scanInsomniaRoot(root,
                    collection != null && collection.name != null ? collection.name : "");
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<UnresolvedVariableIssue> scanBrunoZip(
            byte[] bytes, ApiCollection collection, String collectionName) throws IOException {
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        Set<String> overriddenEnvironmentKeys = new java.util.HashSet<>();
        if (collection != null && collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable != null && variable.enabled && variable.key != null) {
                    overriddenEnvironmentKeys.add(BrunoFormatSupport.renderKey(
                            variable.key, "diagnostic key", new ArrayList<>()));
                }
            }
        }
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                String path = entry.getName();
                if (path.toLowerCase(Locale.ROOT).endsWith(".bru")) {
                    Set<String> shadowed = path.toLowerCase(Locale.ROOT)
                            .endsWith("/environments/environment.bru")
                            ? overriddenEnvironmentKeys : Set.of();
                    scanBrunoText(issues, collectionName, path, text, shadowed);
                } else if (path.toLowerCase(Locale.ROOT).endsWith("bruno.json")) {
                    scanRemainingTokens(issues, collectionName, path, "json", text);
                }
            }
        }
        return dedupeByVariableName(issues);
    }

    private static void scanBrunoText(List<UnresolvedVariableIssue> issues,
                                      String collectionName, String entryPath, String text,
                                      Set<String> shadowedEnvironmentKeys) {
        List<String> lines = physicalLines(text);
        String block = "";
        boolean dictionaryBlock = false;
        boolean skipMultiline = false;
        boolean descriptionMultiline = false;
        StringBuilder pendingDescription = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (skipMultiline) {
                if ("'''".equals(trimmed)) skipMultiline = false;
                continue;
            }
            if (descriptionMultiline) {
                pendingDescription.append('\n').append(line);
                if ("''')".equals(trimmed)) descriptionMultiline = false;
                continue;
            }
            if (line.equals("}")) {
                block = "";
                dictionaryBlock = false;
                pendingDescription = null;
                continue;
            }
            if (line.equals(trimmed) && line.endsWith("{") && line.length() > 1) {
                block = line.substring(0, line.length() - 1).trim();
                dictionaryBlock = !isBruTextBlock(block);
                scanRemainingTokens(issues, collectionName, entryPath, "block", line);
                continue;
            }
            if (!dictionaryBlock) {
                scanRemainingTokens(issues, collectionName, entryPath,
                        block + ":" + (index + 1), line);
                continue;
            }
            if (trimmed.startsWith("@description(")) {
                pendingDescription = new StringBuilder(line);
                descriptionMultiline = trimmed.startsWith("@description('''")
                        && !trimmed.endsWith("''')");
                continue;
            }
            int colon = dictionaryColon(line);
            if (colon >= 0 && !trimmed.startsWith("@")) {
                boolean disabled = trimmed.startsWith("~");
                String renderedKey = line.substring(0, colon).trim();
                if (renderedKey.startsWith("~")) renderedKey = renderedKey.substring(1);
                boolean shadowed = shadowedEnvironmentKeys.contains(renderedKey);
                if (!disabled && !shadowed) {
                    if (pendingDescription != null) {
                        scanRemainingTokens(issues, collectionName, entryPath,
                                block + ":description:" + (index + 1), pendingDescription.toString());
                    }
                    scanRemainingTokens(issues, collectionName, entryPath,
                            block + ":" + (index + 1), line);
                }
                pendingDescription = null;
                if (line.substring(colon + 1).trim().equals("'''")) {
                    skipMultiline = disabled || shadowed;
                }
                continue;
            }
            scanRemainingTokens(issues, collectionName, entryPath,
                    block + ":" + (index + 1), line);
        }
    }

    private static boolean isBruTextBlock(String block) {
        return block.startsWith("script:") || block.equals("tests") || block.equals("test")
                || block.equals("assert") || block.equals("body:test")
                || block.equals("body:json") || block.equals("body:text")
                || block.equals("body:xml") || block.equals("body:graphql")
                || block.equals("body:graphql:vars");
    }

    private static int dictionaryColon(String line) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                int slashes = 0;
                for (int back = index - 1; back >= 0 && line.charAt(back) == '\\'; back--) slashes++;
                if ((slashes & 1) == 0) quoted = !quoted;
            } else if (ch == ':' && !quoted) return index;
        }
        return -1;
    }

    private static List<String> physicalLines(String text) {
        return java.util.Arrays.asList((text != null ? text : "").split("\\r\\n|\\n|\\r", -1));
    }

    private static List<UnresolvedVariableIssue> scanInsomniaRoot(
            JsonElement rootElement, String collectionName) {
        List<UnresolvedVariableIssue> issues = new ArrayList<>();
        if (rootElement == null || !rootElement.isJsonObject()) return issues;
        JsonArray resources = rootElement.getAsJsonObject().getAsJsonArray("resources");
        if (resources == null) return issues;
        for (JsonElement element : resources) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject resource = element.getAsJsonObject();
            String type = jsonString(resource, "_type");
            String name = jsonString(resource, "name");
            String label = name != null ? name : type;
            switch (type != null ? type : "") {
                case "workspace" -> {
                    scanJsonProperty(issues, collectionName, label, "workspace:name", resource, "name");
                    scanJsonProperty(issues, collectionName, label, "workspace:description", resource, "description");
                }
                case "environment" -> scanJsonObject(issues, collectionName, label,
                        "environment", resource.get("data"), true);
                case "request_group", "folder" -> {
                    scanJsonProperty(issues, collectionName, label, "folder:name", resource, "name");
                    scanJsonProperty(issues, collectionName, label, "folder:description", resource, "description");
                    scanJsonObject(issues, collectionName, label, "folder:environment",
                            resource.get("environment"), true);
                    scanActiveAuth(issues, collectionName, label, "folder:auth", resource.get("authentication"));
                    scanJsonProperty(issues, collectionName, label, "folder:script:pre", resource, "preRequestScript");
                    scanJsonProperty(issues, collectionName, label, "folder:script:post", resource, "afterResponseScript");
                }
                case "request" -> scanInsomniaRequest(issues, collectionName, label, resource);
                default -> { }
            }
        }
        return dedupeByVariableName(issues);
    }

    private static void scanInsomniaRequest(List<UnresolvedVariableIssue> issues,
                                            String collectionName, String requestName,
                                            JsonObject request) {
        for (String property : List.of("name", "method", "url", "description")) {
            scanJsonProperty(issues, collectionName, requestName, "request:" + property, request, property);
        }
        scanActiveRows(issues, collectionName, requestName, "header", request.get("headers"));
        scanActiveRows(issues, collectionName, requestName, "parameter", request.get("parameters"));
        scanActiveRows(issues, collectionName, requestName, "path-parameter", request.get("pathParameters"));
        JsonElement bodyElement = request.get("body");
        if (bodyElement != null && bodyElement.isJsonObject()) {
            JsonObject body = bodyElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                if ("params".equals(entry.getKey())) {
                    scanActiveRows(issues, collectionName, requestName, "body:params", entry.getValue());
                } else {
                    scanJsonObject(issues, collectionName, requestName,
                            "body:" + entry.getKey(), entry.getValue(), true);
                }
            }
        }
        scanActiveAuth(issues, collectionName, requestName, "request:auth", request.get("authentication"));
        scanJsonProperty(issues, collectionName, requestName, "script:pre", request, "preRequestScript");
        scanJsonProperty(issues, collectionName, requestName, "script:post", request, "afterResponseScript");
    }

    private static void scanActiveRows(List<UnresolvedVariableIssue> issues,
                                       String collectionName, String requestName,
                                       String location, JsonElement rows) {
        if (rows == null || !rows.isJsonArray()) return;
        int index = 0;
        for (JsonElement row : rows.getAsJsonArray()) {
            if (row != null && row.isJsonObject()
                    && !jsonBoolean(row.getAsJsonObject(), "disabled")) {
                scanJsonObject(issues, collectionName, requestName,
                        location + "[" + index + "]", row, true);
            }
            index++;
        }
    }

    private static void scanActiveAuth(List<UnresolvedVariableIssue> issues,
                                       String collectionName, String requestName,
                                       String location, JsonElement auth) {
        if (auth == null || !auth.isJsonObject()) return;
        JsonObject object = auth.getAsJsonObject();
        String type = jsonString(object, "type");
        if (jsonBoolean(object, "disabled") || "none".equalsIgnoreCase(type)
                || "noauth".equalsIgnoreCase(type)) return;
        scanJsonObject(issues, collectionName, requestName, location, auth, true);
    }

    private static void scanJsonProperty(List<UnresolvedVariableIssue> issues,
                                         String collectionName, String requestName,
                                         String location, JsonObject object, String property) {
        if (object != null && object.has(property)) {
            scanJsonObject(issues, collectionName, requestName, location, object.get(property), true);
        }
    }

    private static void scanJsonObject(List<UnresolvedVariableIssue> issues,
                                       String collectionName, String requestName,
                                       String location, JsonElement element, boolean scanKeys) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                scanRemainingTokens(issues, collectionName, requestName, location, element.getAsString());
            }
            return;
        }
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                scanJsonObject(issues, collectionName, requestName,
                        location + "[" + index++ + "]", child, scanKeys);
            }
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (scanKeys) scanRemainingTokens(issues, collectionName, requestName,
                    location + ":key", entry.getKey());
            scanJsonObject(issues, collectionName, requestName,
                    location + "." + entry.getKey(), entry.getValue(), scanKeys);
        }
    }

    private static String jsonString(JsonObject object, String property) {
        JsonElement value = object != null ? object.get(property) : null;
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static boolean jsonBoolean(JsonObject object, String property) {
        JsonElement value = object != null ? object.get(property) : null;
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static void scanRemainingTokens(List<UnresolvedVariableIssue> issues,
                                            String collectionName, String requestName,
                                            String location, String emittedValue) {
        if (emittedValue == null || emittedValue.isEmpty()) return;
        Matcher matcher = VARIABLE_PATTERN.matcher(emittedValue);
        while (matcher.find()) {
            String variableName = matcher.group(1) != null ? matcher.group(1).trim() : "";
            if (variableName.isEmpty() || matcher.group(2) != null) continue;
            issues.add(new UnresolvedVariableIssue(
                    collectionName != null ? collectionName : "",
                    requestName != null ? requestName : "",
                    variableName,
                    location,
                    "Variable \"" + variableName + "\" remains unresolved in the emitted target artifact."));
        }
    }

    private static void scanRequestValues(List<UnresolvedVariableIssue> issues, ApiCollection collection,
                                          ApiRequest request, VariableResolver resolver,
                                          CollectionExportFormat format) {
        String collectionName = collection.name;
        scanValue(issues, collectionName, request.name, "method", request.method, resolver);
        scanValue(issues, collectionName, request.name, "url", request.url, resolver);
        scanValue(issues, collectionName, request.name, "description", request.description, resolver);
        if (request.parameters != null) for (ApiRequest.Parameter parameter : request.parameters) if (parameter != null && !parameter.disabled) {
            scanValue(issues, collectionName, request.name, "parameter:key", parameter.key, resolver);
            scanValue(issues, collectionName, request.name, "parameter:value", parameter.value, resolver);
        }
        if (request.headers != null) for (ApiRequest.Header header : request.headers) if (header != null && !header.disabled) {
            scanValue(issues, collectionName, request.name, "header:key", header.key, resolver);
            scanValue(issues, collectionName, request.name, "header:value", header.value, resolver);
        }
        if (request.body != null) {
            String mode = request.body.mode != null ? request.body.mode.toLowerCase(Locale.ROOT) : "none";
            if ("raw".equals(mode) || "file".equals(mode))
                scanValue(issues, collectionName, request.name, "body:raw", request.body.raw, resolver);
            if ("graphql".equals(mode) && request.body.graphql != null) {
                scanValue(issues, collectionName, request.name, "body:graphql", request.body.graphql.query, resolver);
                scanValue(issues, collectionName, request.name, "body:graphql:variables", request.body.graphql.variables, resolver);
            }
            if ("urlencoded".equals(mode)) scanFormValues(issues, collectionName, request.name, request.body.urlencoded, resolver);
            if ("formdata".equals(mode)) scanFormValues(issues, collectionName, request.name, request.body.formdata, resolver);
        }
        String authMode = AuthInheritanceResolver.normalizeAuthOverrideMode(request.authOverrideMode, request);
        if ("explicit".equals(authMode)) {
            ApiRequest.Auth auth = request.explicitAuth != null ? request.explicitAuth : request.auth;
            scanAuthValues(issues, collectionName, request.name, "auth", auth, resolver, format);
        }
        if (request.scriptBlocks != null) for (burp.scripts.ScriptBlock block : request.scriptBlocks)
            if (block != null && block.enabled) scanValue(issues, collectionName, request.name,
                    "script:" + block.phase, block.source, resolver);
        boolean nativePre = request.scriptBlocks != null && request.scriptBlocks.stream()
                .anyMatch(block -> block != null && block.phase == burp.scripts.ScriptPhase.PRE_REQUEST);
        boolean nativePost = request.scriptBlocks != null && request.scriptBlocks.stream()
                .anyMatch(block -> block != null && block.phase == burp.scripts.ScriptPhase.POST_RESPONSE);
        if (!nativePre && request.preRequestScripts != null) for (ApiRequest.Script script : request.preRequestScripts)
            if (script != null) scanValue(issues, collectionName, request.name, "script:pre-request", script.exec, resolver);
        if (!nativePost && request.postResponseScripts != null) for (ApiRequest.Script script : request.postResponseScripts)
            if (script != null) scanValue(issues, collectionName, request.name, "script:post-response", script.exec, resolver);
    }

    private static void scanFormValues(List<UnresolvedVariableIssue> issues, String collectionName,
                                       String requestName, List<ApiRequest.Body.FormField> fields,
                                       VariableResolver resolver) {
        if (fields == null) return;
        for (ApiRequest.Body.FormField field : fields) if (field != null && !field.disabled) {
            scanValue(issues, collectionName, requestName, "form:key", field.key, resolver);
            scanValue(issues, collectionName, requestName, "form:value", field.value, resolver);
            scanValue(issues, collectionName, requestName, "form:file", field.filePath, resolver);
        }
    }

    private static void scanCollectionLevelValues(List<UnresolvedVariableIssue> issues,
                                                  ApiCollection collection,
                                                  VariableResolver resolver,
                                                  CollectionExportFormat format) {
        if (collection == null) {
            return;
        }
        String collectionName = collection.name != null ? collection.name : "";
        scanAuthValues(issues, collectionName, collectionName, "collection-auth",
                collection.auth, resolver, format);
        if (collection.folderAuth != null) {
            for (Map.Entry<String, ApiRequest.Auth> entry : collection.folderAuth.entrySet()) {
                String folder = AuthInheritanceResolver.normalizeFolderPath(entry.getKey());
                String mode = collection.folderAuthModes != null ? collection.folderAuthModes.get(folder) : null;
                if (!"explicit".equals(AuthInheritanceResolver.normalizeAuthOverrideMode(mode, null))) continue;
                scanAuthValues(issues, collectionName, collectionName, "folder-auth:" + safe(folder),
                        entry.getValue(), resolver, format);
            }
        }
        if (collection.variables != null) {
            for (ApiRequest.Variable variable : collection.variables) {
                if (variable == null) {
                    continue;
                }
                if (variable.enabled) scanValue(issues, collectionName, collectionName, "collection-variable:" + safe(variable.key), variable.value, resolver);
            }
        }
        if (collection.folderVars != null) {
            for (Map.Entry<String, Map<String, String>> entry : collection.folderVars.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, String> nested : entry.getValue().entrySet()) {
                    scanValue(issues, collectionName, collectionName, "folder-variable:" + safe(entry.getKey()) + ":" + safe(nested.getKey()), nested.getValue(), resolver);
                }
            }
        }
        if (collection.environment != null) {
            for (Map.Entry<String, String> entry : collection.environment.entrySet()) {
                scanValue(issues, collectionName, collectionName, "collection-environment:" + safe(entry.getKey()), entry.getValue(), resolver);
            }
        }
        if (format != CollectionExportFormat.INSOMNIA_JSON) {
            if (collection.scriptBlocks != null) for (burp.scripts.ScriptBlock block : collection.scriptBlocks)
                if (block != null && block.enabled) scanValue(issues, collectionName, collectionName,
                        "collection-script:" + block.phase, block.source, resolver);
        }
        if (collection.folderScriptBlocks != null) for (Map.Entry<String, List<burp.scripts.ScriptBlock>> entry : collection.folderScriptBlocks.entrySet())
            if (entry.getValue() != null) for (burp.scripts.ScriptBlock block : entry.getValue())
                if (block != null && block.enabled) scanValue(issues, collectionName, collectionName,
                        "folder-script:" + safe(entry.getKey()) + ":" + block.phase, block.source, resolver);
    }

    private static void scanAuthValues(List<UnresolvedVariableIssue> issues, String collectionName,
                                       String requestName, String location, ApiRequest.Auth auth,
                                       VariableResolver resolver, CollectionExportFormat format) {
        if (auth == null || auth.type == null || auth.properties == null) return;
        String type = auth.type.toLowerCase(Locale.ROOT);
        if ("none".equals(type) || "noauth".equals(type) || "inherit".equals(type)) return;
        for (Map.Entry<String, String> entry : auth.properties.entrySet()) {
            if (entry.getKey() != null && exportedAuthProperty(format, type, entry.getKey())) {
                scanValue(issues, collectionName, requestName,
                        location + ":" + safe(entry.getKey()), entry.getValue(), resolver);
            }
        }
    }

    private static boolean exportedAuthProperty(CollectionExportFormat format, String type, String key) {
        if (format == null) return true;
        Set<String> supported = switch (type) {
            case "basic" -> Set.of("username", "user", "password", "pass");
            case "bearer" -> Set.of("token", "value", "access_token");
            case "apikey", "api_key" -> Set.of("key", "name", "value", "token", "in", "placement", "addTo");
            case "oauth2" -> Set.of("grantType", "grant_type", "accessTokenUrl", "access_token_url",
                    "authorizationUrl", "authorization_url", "redirectUri", "redirectUrl", "callback_url",
                    "clientId", "client_id", "clientSecret", "client_secret", "scope", "username",
                    "password", "accessToken", "access_token");
            default -> format == CollectionExportFormat.INSOMNIA_JSON ? null : Set.of();
        };
        return supported == null || supported.contains(key);
    }

    private static void scanValues(List<UnresolvedVariableIssue> issues,
                                   String collectionName,
                                   String requestName,
                                   String location,
                                   List<String> values,
                                   VariableResolver resolver) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            scanValue(issues, collectionName, requestName, location, value, resolver);
        }
    }

    private static void scanValue(List<UnresolvedVariableIssue> issues,
                                  String collectionName,
                                  String requestName,
                                  String location,
                                  String input,
                                  VariableResolver resolver) {
        if (input == null || input.isEmpty()) {
            return;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        while (matcher.find()) {
            String variableName = matcher.group(1) != null ? matcher.group(1).trim() : "";
            String defaultValue = matcher.group(2);
            if (variableName.isEmpty() || defaultValue != null) {
                continue;
            }
            String token = "{{" + variableName + "}}";
            String resolved = resolver != null ? resolver.resolve(token) : token;
            if (resolved != null && !token.equals(resolved) && !resolved.isBlank()) continue;
            issues.add(new UnresolvedVariableIssue(
                    collectionName != null ? collectionName : "",
                    requestName != null ? requestName : "",
                    variableName,
                    location,
                    resolved != null && !token.equals(resolved)
                            ? "Variable \"" + variableName + "\" exists in the export scope but has an empty value."
                            : "Variable \"" + variableName + "\" is unresolved."
            ));
        }
    }

    private static List<UnresolvedVariableIssue> dedupe(List<UnresolvedVariableIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<UnresolvedVariableIssue> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (UnresolvedVariableIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String key = issue.collectionName + "\u0000" + issue.requestName + "\u0000" + issue.variableName + "\u0000" + issue.location;
            if (seen.add(key)) {
                out.add(issue);
            }
        }
        return out;
    }

    private static List<UnresolvedVariableIssue> dedupeByVariableName(List<UnresolvedVariableIssue> issues) {
        if (issues == null || issues.isEmpty()) return List.of();
        List<UnresolvedVariableIssue> out = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (UnresolvedVariableIssue issue : issues) {
            if (issue != null && seen.add(issue.variableName)) out.add(issue);
        }
        return out;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
