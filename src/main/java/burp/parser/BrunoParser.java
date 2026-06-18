package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import burp.utils.AuthInheritanceResolver;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.script.*;

/**
 * Parser for Bruno collections (.bru files).
 * Bruno stores each request as a separate .bru file in a folder structure.
 */
public class BrunoParser implements CollectionParser {
    private static final Pattern BRUNO_STANDARD_METHOD_PATTERN = Pattern.compile(
            "^\\s*(get|post|put|delete|patch|head|options|trace|connect)\\s*(\\{|[^\\n]*$)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern BRUNO_CUSTOM_METHOD_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*\\{",
            Pattern.MULTILINE);
    private static final Set<String> BRUNO_RESERVED_TOKENS = Set.of(
            "meta", "vars", "headers", "auth", "body", "script", "assert",
            "get", "post", "put", "delete", "patch", "head", "options", "trace", "connect");

    @Override
    public boolean canParse(File file) {
        // Bruno collection is a directory containing .bru files
        if (file.isDirectory()) {
            try {
                return Files.walk(file.toPath())
                    .anyMatch(p -> p.toString().endsWith(".bru"));
            } catch (Exception e) {
                return false;
            }
        }
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".bru")) {
            return true;
        }
        if (lowerName.endsWith(".zip")) {
            return looksLikeBrunoZip(file);
        }
        return false;
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.format = "bruno";
        collection.name = file.getName().replace(".bru", "");
        Path extractedZipRoot = null;
        try {
            if (isBrunoZip(file)) {
                extractedZipRoot = Files.createTempDirectory("bruno-import-");
                extractBrunoZip(file.toPath(), extractedZipRoot);
                Path brunoRoot = determineBrunoRoot(extractedZipRoot);
                if (brunoRoot == null) {
                    brunoRoot = extractedZipRoot;
                }
                parseBrunoDirectory(brunoRoot.toFile(), collection);
                loadBrunoJsonIfPresent(brunoRoot.toFile(), collection);
            } else if (file.isDirectory()) {
                parseBrunoDirectory(file, collection);
                loadBrunoJsonIfPresent(file, collection);
            } else {
                String content = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (looksLikeRequestBru(content)) {
                    ApiRequest req = parseBruFile(file, "");
                    if (req != null) {
                        req.sourceCollection = collection.name;
                        collection.requests.add(req);
                    }
                } else {
                    Map<String, String> vars = parseVarsBlock(content);
                    putCollectionVariables(collection, vars);
                }
                loadBrunoJsonIfPresent(file.getParentFile(), collection);
            }

            for (ApiRequest request : collection.requests) {
                if (request != null) {
                    request.sourceCollection = collection.name;
                }
            }
            AuthInheritanceResolver.recomputeCollectionAuth(collection);
            return collection;
        } finally {
            if (extractedZipRoot != null) {
                deleteRecursivelyQuietly(extractedZipRoot);
            }
        }
    }

    private boolean isBrunoZip(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".zip") && looksLikeBrunoZip(file);
    }

    private boolean looksLikeBrunoZip(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry == null || entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName() != null ? entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT) : "";
                if (entryName.endsWith(".bru") || entryName.endsWith("bruno.json")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private void extractBrunoZip(Path zipPath, Path targetDir) throws IOException {
        if (zipPath == null || targetDir == null) {
            return;
        }
        Files.createDirectories(targetDir);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path determineBrunoRoot(Path extractedRoot) throws IOException {
        if (extractedRoot == null || !Files.isDirectory(extractedRoot)) {
            return extractedRoot;
        }
        Path current = extractedRoot;
        while (current != null && Files.isDirectory(current)) {
            if (hasImmediateBrunoMetadata(current)) {
                return current;
            }
            List<Path> childDirectories = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                for (Path child : stream) {
                    if (child != null && Files.isDirectory(child)) {
                        childDirectories.add(child);
                    }
                }
            }
            if (childDirectories.size() != 1) {
                return current;
            }
            current = childDirectories.get(0);
        }
        return current;
    }

    private boolean hasImmediateBrunoMetadata(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (child == null || Files.isDirectory(child)) {
                    continue;
                }
                String lowerName = child.getFileName() != null ? child.getFileName().toString().toLowerCase(Locale.ROOT) : "";
                if (lowerName.endsWith(".bru") || "bruno.json".equals(lowerName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadBrunoJsonIfPresent(File root, ApiCollection collection) {
        if (root == null || collection == null) {
            return;
        }
        File brunoJson = findBrunoJson(root);
        if (brunoJson == null || !brunoJson.exists()) {
            return;
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(brunoJson), java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("name")) {
                collection.name = obj.get("name").getAsString();
            }
            String[] envKeys = {"vars", "variables", "env"};
            for (String envKey : envKeys) {
                if (obj.has(envKey) && obj.get(envKey).isJsonObject()) {
                    com.google.gson.JsonObject envObj = obj.getAsJsonObject(envKey);
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : envObj.entrySet()) {
                        com.google.gson.JsonElement value = entry.getValue();
                        if (value != null && !value.isJsonNull()) {
                            if (value.isJsonPrimitive()) {
                                collection.environment.put(entry.getKey(), value.getAsString());
                            } else {
                                collection.environment.put(entry.getKey(), value.toString());
                            }
                        }
                    }
                }
            }
            if (obj.has("presets") && obj.get("presets").isJsonObject()) {
                com.google.gson.JsonObject presets = obj.getAsJsonObject("presets");
                for (String presetKey : presets.keySet()) {
                    if (presets.get(presetKey).isJsonObject()) {
                        com.google.gson.JsonObject preset = presets.getAsJsonObject(presetKey);
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : preset.entrySet()) {
                            com.google.gson.JsonElement value = entry.getValue();
                            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                                collection.environment.putIfAbsent(entry.getKey(), value.getAsString());
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private File findBrunoJson(File root) {
        if (root == null) {
            return null;
        }
        File direct = new File(root, "bruno.json");
        if (direct.exists()) {
            return direct;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
            return paths
                    .filter(path -> path != null && path.getFileName() != null && "bruno.json".equalsIgnoreCase(path.getFileName().toString()))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void deleteRecursivelyQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private void parseBrunoDirectory(File root, ApiCollection collection) throws IOException {
        File rootDir = root != null ? root : null;
        if (rootDir == null || collection == null) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(rootDir.toPath())) {
            paths.forEach(path -> {
                if (!path.toString().endsWith(".bru")) {
                    return;
                }
                try {
                    String content = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
                    String folderPath = normalizeBrunoMetadataPath(rootDir.toPath(), path.getParent());
                    if (looksLikeRequestBru(content)) {
                        ApiRequest req = parseBruFile(path.toFile(), folderPath);
                        if (req != null) {
                            req.sourceCollection = collection.name;
                            collection.requests.add(req);
                        }
                    } else {
                        Map<String, String> vars = parseVarsBlock(content);
                        if (!vars.isEmpty()) {
                            if (folderPath.isEmpty()) {
                                putCollectionVariables(collection, vars);
                            } else {
                                putFolderVariables(collection, folderPath, vars);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed files
                }
            });
        }
    }

    private ApiRequest parseBruFile(File file, String folderPath) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            ApiRequest req = new ApiRequest();
            req.name = file.getName().replace(".bru", "");

            // Parse meta block
            Pattern metaPattern = Pattern.compile("meta\\s*\\{\\s*name:\\s*(.+?)\\s*type:\\s*(.+?)\\s*seq:\\s*(\\d+)\\s*\\}");
            Matcher metaMatcher = metaPattern.matcher(content);
            if (metaMatcher.find()) {
                req.name = metaMatcher.group(1).trim();
                req.sequenceOrder = Integer.parseInt(metaMatcher.group(3).trim());
            }
            req.path = folderPath.isEmpty() ? req.name : folderPath + "/" + req.name;

            BrunoMethodMatch methodMatch = extractBrunoMethod(content);
            if (methodMatch != null) {
                req.method = methodMatch.method;
                if (methodMatch.sameLineUrl != null && !methodMatch.sameLineUrl.isBlank()) {
                    req.url = methodMatch.sameLineUrl;
                }
            }

            // Extract URL from url: line inside the method block
            Pattern urlPattern = Pattern.compile("^\\s*url\\s*[:]?\\s*(.+?)$", Pattern.MULTILINE);
            Matcher urlMatcher = urlPattern.matcher(content);
            if (urlMatcher.find()) {
                req.url = urlMatcher.group(1).trim();
            }

            // Parse headers
            Pattern headersPattern = Pattern.compile("headers\\s*\\{([^}]+)\\}");
            Matcher headersMatcher = headersPattern.matcher(content);
            if (headersMatcher.find()) {
                String headersBlock = headersMatcher.group(1);
                for (String line : headersBlock.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    boolean disabled = false;
                    if (line.startsWith("~")) {
                        disabled = true;
                        line = line.substring(1).trim();
                    }
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).trim();
                        String value = line.substring(colonIdx + 1).trim();
                        req.headers.add(new ApiRequest.Header(key, value, disabled));
                    }
                }
            }

            // Parse body
            String bodyBlock = extractBlock(content, "body");
            if (bodyBlock != null) {
                req.body = new ApiRequest.Body();
                req.body.mode = "raw";
                req.body.raw = bodyBlock;
                req.body.contentType = "text/plain";

                // Detect content type from headers
                for (ApiRequest.Header h : req.headers) {
                    if (h.key.equalsIgnoreCase("content-type")) {
                        req.body.contentType = h.value;
                        break;
                    }
                }
            } else {
                Pattern bodyPattern = Pattern.compile("body\\s*[:]?\\s*(none|json|xml|text|graphql|form|multipart)?", Pattern.CASE_INSENSITIVE);
                Matcher bodyMatcher = bodyPattern.matcher(content);
                if (bodyMatcher.find() && bodyMatcher.group(1) != null) {
                    // Mode-only body (e.g., body: none)
                    req.body = new ApiRequest.Body();
                    req.body.mode = "none";
                }
            }

            // Parse auth (basic, bearer, apikey, oauth2)
            String authBlock = extractBlock(content, "auth");
            if (authBlock != null) {
                ApiRequest.Auth parsedAuth = parseBrunoAuthBlock(authBlock);
                if ("none".equalsIgnoreCase(AuthInheritanceResolver.normalizeParsedAuthMode(parsedAuth))) {
                    AuthInheritanceResolver.markRequestNoAuth(req);
                } else {
                    AuthInheritanceResolver.markRequestExplicitAuth(req, parsedAuth);
                }
            } else {
                AuthInheritanceResolver.markRequestInherit(req);
            }

            // Parse vars (pre-request variables)
            String varsBlock = extractBlock(content, "vars");
            if (varsBlock != null) {
                for (String line : varsBlock.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        ApiRequest.Variable v = new ApiRequest.Variable();
                        v.key = line.substring(0, colonIdx).trim();
                        v.value = line.substring(colonIdx + 1).trim();
                        req.variables.add(v);
                    }
                }
            }

            // Parse assert (test scripts)
            String assertBlock = extractBlock(content, "assert");
            if (assertBlock != null) {
                ApiRequest.Script script = new ApiRequest.Script("js", assertBlock);
                req.postResponseScripts.add(script);
                addScriptBlock(req, script, ScriptDialect.BRUNO, ScriptPhase.TEST, file, 0);
            }

            // Parse script:pre-request
            String preScriptBlock = extractBlock(content, "script:pre-request");
            if (preScriptBlock != null) {
                String script = preScriptBlock;
                // Normalize Bruno script to standard JS
                script = normalizeBrunoScript(script);
                ApiRequest.Script legacy = new ApiRequest.Script("js", script);
                req.preRequestScripts.add(legacy);
                addScriptBlock(req, legacy, ScriptDialect.BRUNO, ScriptPhase.PRE_REQUEST, file, 1);
            }

            // Parse script:post-response
            String postScriptBlock = extractBlock(content, "script:post-response");
            if (postScriptBlock != null) {
                String script = postScriptBlock;
                script = normalizeBrunoScript(script);
                ApiRequest.Script legacy = new ApiRequest.Script("js", script);
                req.postResponseScripts.add(legacy);
                addScriptBlock(req, legacy, ScriptDialect.BRUNO, ScriptPhase.POST_RESPONSE, file, 2);
            }

            return req;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksLikeRequestBru(String content) {
        return extractBrunoMethod(content) != null;
    }

    private BrunoMethodMatch extractBrunoMethod(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        Matcher standardMatcher = BRUNO_STANDARD_METHOD_PATTERN.matcher(content);
        if (standardMatcher.find()) {
            String method = standardMatcher.group(1) != null ? standardMatcher.group(1).trim().toUpperCase() : null;
            String sameLineUrl = standardMatcher.group(2) != null ? standardMatcher.group(2).trim() : "";
            return method != null ? new BrunoMethodMatch(method, sameLineUrl) : null;
        }

        if (!hasHttpMeta(content)) {
            return null;
        }

        Matcher customMatcher = BRUNO_CUSTOM_METHOD_PATTERN.matcher(content);
        while (customMatcher.find()) {
            String methodToken = customMatcher.group(1) != null ? customMatcher.group(1).trim() : "";
            if (methodToken.isEmpty() || BRUNO_RESERVED_TOKENS.contains(methodToken.toLowerCase())) {
                continue;
            }
            return new BrunoMethodMatch(methodToken.toUpperCase(), "");
        }
        return null;
    }

    private boolean hasHttpMeta(String content) {
        String metaBlock = extractBlock(content, "meta");
        if (metaBlock == null) {
            return false;
        }
        Pattern typePattern = Pattern.compile("^\\s*type\\s*:\\s*http\\s*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        return typePattern.matcher(metaBlock).find();
    }

    private String normalizeBrunoMetadataPath(Path root, Path parent) {
        if (root == null || parent == null) {
            return "";
        }
        Path relative = root.relativize(parent);
        String value = relative.toString().replace(File.separatorChar, '/').trim();
        if (".".equals(value)) {
            return "";
        }
        return AuthInheritanceResolver.normalizeFolderPath(value);
    }

    private Map<String, String> parseVarsBlock(String content) {
        Map<String, String> vars = new LinkedHashMap<>();
        String varsBlock = extractBlock(content, "vars");
        if (varsBlock == null) {
            return vars;
        }
        for (String rawLine : varsBlock.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) {
                continue;
            }
            String key = line.substring(0, colonIdx).trim();
            if (key.isEmpty()) {
                continue;
            }
            String value = line.substring(colonIdx + 1).trim();
            vars.put(key, value);
        }
        return vars;
    }

    private void putCollectionVariables(ApiCollection collection, Map<String, String> vars) {
        if (collection == null || vars == null || vars.isEmpty()) {
            return;
        }
        Map<String, ApiRequest.Variable> byKey = new LinkedHashMap<>();
        for (ApiRequest.Variable existing : collection.variables) {
            if (existing != null && existing.key != null) {
                byKey.put(existing.key, existing);
            }
        }
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            ApiRequest.Variable variable = byKey.get(entry.getKey());
            if (variable == null) {
                variable = new ApiRequest.Variable();
                variable.key = entry.getKey();
                variable.type = "string";
                variable.enabled = true;
                collection.variables.add(variable);
                byKey.put(entry.getKey(), variable);
            }
            variable.value = entry.getValue();
        }
    }

    private void putFolderVariables(ApiCollection collection, String folderPath, Map<String, String> vars) {
        if (collection == null || vars == null || vars.isEmpty()) {
            return;
        }
        String normalizedPath = AuthInheritanceResolver.normalizeFolderPath(folderPath);
        if (normalizedPath.isEmpty()) {
            return;
        }
        if (collection.folderVars == null) {
            collection.folderVars = new LinkedHashMap<>();
        }
        Map<String, String> existing = collection.folderVars.get(normalizedPath);
        if (existing == null) {
            existing = new LinkedHashMap<>();
            collection.folderVars.put(normalizedPath, existing);
        }
        existing.putAll(vars);
    }

    private static final class BrunoMethodMatch {
        private final String method;
        private final String sameLineUrl;

        private BrunoMethodMatch(String method, String sameLineUrl) {
            this.method = method;
            this.sameLineUrl = sameLineUrl;
        }
    }

    /**
     * Extracts the inner content of a named block by counting braces.
     * Returns null if the block is not found.
     */
    private String extractBlock(String content, String blockName) {
        Pattern startPattern = Pattern.compile("\\b" + Pattern.quote(blockName) + "\\s*\\{", Pattern.CASE_INSENSITIVE);
        Matcher m = startPattern.matcher(content);
        if (!m.find()) return null;
        int start = m.end() - 1; // position of opening brace
        int braceCount = 0;
        int i = start;
        for (; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) break;
            }
        }
        if (braceCount != 0) return null;
        return content.substring(start + 1, i).trim();
    }

    /**
     * Parses a Bruno auth block content into an ApiRequest.Auth object.
     */
    private ApiRequest.Auth parseBrunoAuthBlock(String authContent) {
        String flatMode = extractValue(authContent, "mode");
        if (flatMode == null) {
            flatMode = extractValue(authContent, "type");
        }
        if (flatMode != null) {
            String normalized = flatMode.trim().toLowerCase();
            switch (normalized) {
                case "none":
                case "noauth":
                case "no_auth": {
                    ApiRequest.Auth auth = new ApiRequest.Auth();
                    auth.type = "none";
                    return auth;
                }
                case "basic": {
                    ApiRequest.Auth auth = new ApiRequest.Auth();
                    auth.type = "basic";
                    auth.properties.put("username", extractValue(authContent, "username"));
                    auth.properties.put("password", extractValue(authContent, "password"));
                    return auth;
                }
                case "bearer": {
                    ApiRequest.Auth auth = new ApiRequest.Auth();
                    auth.type = "bearer";
                    auth.properties.put("token", extractValue(authContent, "token"));
                    return auth;
                }
                case "apikey": {
                    ApiRequest.Auth auth = new ApiRequest.Auth();
                    auth.type = "apikey";
                    auth.properties.put("key", extractValue(authContent, "key"));
                    auth.properties.put("value", extractValue(authContent, "value"));
                    String placement = extractValue(authContent, "placement");
                    auth.properties.put("in", placement != null ? placement : "header");
                    return auth;
                }
                case "oauth2": {
                    ApiRequest.Auth auth = new ApiRequest.Auth();
                    auth.type = "oauth2";
                    String grantType = extractValue(authContent, "grant_type");
                    auth.properties.put("grantType", grantType != null ? grantType : "client_credentials");
                    auth.properties.put("accessTokenUrl", extractValue(authContent, "access_token_url"));
                    auth.properties.put("authorizationUrl", extractValue(authContent, "authorization_url"));
                    auth.properties.put("clientId", extractValue(authContent, "client_id"));
                    auth.properties.put("clientSecret", extractValue(authContent, "client_secret"));
                    auth.properties.put("scope", extractValue(authContent, "scope"));
                    auth.properties.put("accessToken", extractValue(authContent, "access_token"));
                    return auth;
                }
            }
        }
        // Explicit no-auth blocks in Bruno syntax.
        if (extractBlock(authContent, "none") != null
                || extractBlock(authContent, "noauth") != null
                || extractBlock(authContent, "no_auth") != null) {
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "none";
            return auth;
        }
        // Try basic
        String basicBlock = extractBlock(authContent, "basic");
        if (basicBlock != null) {
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "basic";
            auth.properties.put("username", extractValue(basicBlock, "username"));
            auth.properties.put("password", extractValue(basicBlock, "password"));
            return auth;
        }
        // Try bearer
        String bearerBlock = extractBlock(authContent, "bearer");
        if (bearerBlock != null) {
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "bearer";
            auth.properties.put("token", extractValue(bearerBlock, "token"));
            return auth;
        }
        // Try apikey
        String apikeyBlock = extractBlock(authContent, "apikey");
        if (apikeyBlock != null) {
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "apikey";
            auth.properties.put("key", extractValue(apikeyBlock, "key"));
            auth.properties.put("value", extractValue(apikeyBlock, "value"));
            String placement = extractValue(apikeyBlock, "placement");
            auth.properties.put("in", placement != null ? placement : "header");
            return auth;
        }
        // Try oauth2
        String oauth2Block = extractBlock(authContent, "oauth2");
        if (oauth2Block != null) {
            ApiRequest.Auth auth = new ApiRequest.Auth();
            auth.type = "oauth2";
            String grantType = extractValue(oauth2Block, "grant_type");
            auth.properties.put("grantType", grantType != null ? grantType : "client_credentials");
            auth.properties.put("accessTokenUrl", extractValue(oauth2Block, "access_token_url"));
            auth.properties.put("authorizationUrl", extractValue(oauth2Block, "authorization_url"));
            auth.properties.put("clientId", extractValue(oauth2Block, "client_id"));
            auth.properties.put("clientSecret", extractValue(oauth2Block, "client_secret"));
            auth.properties.put("scope", extractValue(oauth2Block, "scope"));
            auth.properties.put("accessToken", extractValue(oauth2Block, "access_token"));
            return auth;
        }
        return null;
    }

    private String extractValue(String block, String key) {
        Pattern p = Pattern.compile("^\\s*" + key + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(block);
        if (m.find()) {
            String val = m.group(1).trim();
            // Remove surrounding quotes if present
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    /**
     * Normalize Bruno script syntax to standard JavaScript.
     * Converts bru.setVar() to pm.environment.set() for cross-compatibility.
     */
    private void addScriptBlock(ApiRequest req,
                                ApiRequest.Script script,
                                ScriptDialect dialect,
                                ScriptPhase phase,
                                File file,
                                int order) {
        if (req == null || script == null) {
            return;
        }
        ScriptBlock block = ScriptBlock.fromLegacy(
                script,
                dialect,
                phase,
                ScriptScope.REQUEST,
                "bruno",
                file != null ? file.getAbsolutePath() : null,
                order
        );
        if (block != null) {
            req.scriptBlocks.add(block);
        }
    }

    private String normalizeBrunoScript(String script) {
        if (script == null) return "";
        // Convert bru.setVar("key", value) -> pm.environment.set("key", value)
        script = script.replaceAll("bru\\.setVar\\s*\\(", "pm.environment.set(");
        script = script.replaceAll("bru\\.getVar\\s*\\(", "pm.environment.get(");
        script = script.replaceAll("bru\\.setEnvVar\\s*\\(", "pm.environment.set(");
        script = script.replaceAll("bru\\.getEnvVar\\s*\\(", "pm.environment.get(");
        // Convert res.body -> jsonData (for Postman-style test scripts)
        script = script.replaceAll("(?<!\\.)res\\.body", "jsonData");
        script = script.replaceAll("(?<!\\.)res\\.status", "responseCode.code");
        return script;
    }

    @Override
    public String getFormatName() { return "Bruno"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"bru", "zip"}; }
}
