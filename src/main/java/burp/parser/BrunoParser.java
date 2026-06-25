package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.diagnostics.DiagnosticEvent;
import burp.diagnostics.DiagnosticOperation;
import burp.diagnostics.DiagnosticSeverity;
import burp.diagnostics.DiagnosticStore;
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
            "get", "post", "put", "delete", "patch", "head", "options", "trace", "connect",
            "params", "query", "path", "test", "tests");
    private static final Set<String> SUPPORTED_BODY_SELECTORS = Set.of(
            "none", "json", "text", "xml", "graphql", "form-urlencoded", "multipart-form");
    private static final Set<String> SUPPORTED_AUTH_SELECTORS = Set.of(
            "none", "noauth", "no_auth", "basic", "bearer", "apikey", "oauth2");

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
                try {
                    String content = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    BrunoBlockScanner.ScanResult scanResult = BrunoBlockScanner.scanDetailed(content);
                    recordMalformedBrunoScanWarnings(collection, file.toPath().getParent(), file.toPath(), scanResult.malformedBlocks, true);
                    List<BrunoBlockScanner.Block> effectiveBlocks = filterBlocksAfterMalformed(scanResult.blocks, scanResult.malformedBlockStartIndices);
                    if (looksLikeRequestBru(effectiveBlocks, content)) {
                        ApiRequest req = parseBruFile(file, "", content, effectiveBlocks, collection, singleFileDisplayPath(file));
                        if (req != null) {
                            req.sourceCollection = collection.name;
                            collection.requests.add(req);
                            collection.importedRequestCount++;
                        }
                    } else {
                        Map<String, String> vars = parseVarsBlocks(scanResult.blocks);
                        putCollectionVariables(collection, vars);
                        ApiRequest.Auth auth = parseAuthFromBlocks(scanResult.blocks, content);
                        if (auth != null) {
                            applyScopedMetadataAuth(collection, "", auth);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    recordMalformedBrunoFile(collection, file.toPath().getParent(), file.toPath(), e);
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
        } catch (IOException | RuntimeException ignored) {
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
        if (root == null || collection == null) {
            return;
        }
        Path rootPath = root.toPath();
        List<Path> bruFiles;
        try (java.util.stream.Stream<Path> paths = Files.walk(rootPath)) {
            bruFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bru"))
                    .sorted(Comparator.comparing(path -> normalizeRelativeImportPath(rootPath, path)))
                    .toList();
        }

        for (Path path : bruFiles) {
            try {
                String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                String folderPath = normalizeBrunoMetadataPath(rootPath, path.getParent());
                BrunoBlockScanner.ScanResult scanResult = BrunoBlockScanner.scanDetailed(content);
                recordMalformedBrunoScanWarnings(collection, rootPath, path, scanResult.malformedBlocks, true);
                List<BrunoBlockScanner.Block> effectiveBlocks = filterBlocksAfterMalformed(scanResult.blocks, scanResult.malformedBlockStartIndices);
                if (looksLikeRequestBru(effectiveBlocks, content)) {
                    ApiRequest req = parseBruFile(path.toFile(), folderPath, content, effectiveBlocks, collection, normalizeRelativeImportPath(rootPath, path));
                    if (req != null) {
                        req.sourceCollection = collection.name;
                        collection.requests.add(req);
                        if (collection.importedRequestCount < Integer.MAX_VALUE) {
                            collection.importedRequestCount++;
                        }
                    }
                } else {
                    Map<String, String> vars = parseVarsBlocks(scanResult.blocks);
                    if (!vars.isEmpty()) {
                        if (folderPath.isEmpty()) {
                            putCollectionVariables(collection, vars);
                        } else {
                            putFolderVariables(collection, folderPath, vars);
                        }
                    }
                    ApiRequest.Auth folderAuth = parseAuthFromBlocks(scanResult.blocks, content);
                    if (folderAuth != null) {
                        applyScopedMetadataAuth(collection, folderPath, folderAuth);
                    }
                }
            } catch (IOException | RuntimeException e) {
                recordMalformedBrunoFile(collection, rootPath, path, e);
            }
        }

        sortRequestsBySequence(collection);
    }

    private ApiRequest parseBruFile(File file, String folderPath) throws IOException {
        String content = file != null ? Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8) : null;
        BrunoBlockScanner.ScanResult scanResult = BrunoBlockScanner.scanDetailed(content);
        return parseBruFile(file, folderPath, content, scanResult.blocks, null, file != null ? file.getName() : null);
    }

    private ApiRequest parseBruFile(File file, String folderPath, String content, List<BrunoBlockScanner.Block> blocks, ApiCollection collection, String displayPath) throws IOException {
        if (file == null || content == null || blocks == null) {
            return null;
        }

        BrunoMethodMatch methodMatch = extractBrunoMethod(blocks, content);
        if (methodMatch == null) {
            return null;
        }

        String methodBlockContent = methodBlockContentForUrl(blocks, content, methodMatch);
        BrunoBlockScanner.ScanResult nestedScan = methodBlockContent != null ? BrunoBlockScanner.scanDetailed(methodBlockContent) : new BrunoBlockScanner.ScanResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        recordMalformedBrunoScanWarnings(collection, file.toPath().getParent(), file.toPath(), nestedScan.malformedBlocks, true);
        List<BrunoBlockScanner.Block> requestBlocks = new ArrayList<>();
        requestBlocks.addAll(nestedScan.blocks);
        requestBlocks.addAll(blocks);

        ApiRequest req = new ApiRequest();
        req.name = stripBrunoExtension(file.getName());
        req.method = methodMatch.method;
        req.path = folderPath == null || folderPath.isEmpty() ? req.name : folderPath + "/" + req.name;
        if (methodMatch.sameLineUrl != null && !methodMatch.sameLineUrl.isBlank()) {
            req.url = methodMatch.sameLineUrl;
        }

        BrunoBlockScanner.Block metaBlock = BrunoBlockScanner.firstByName(blocks, "meta");
        if (metaBlock != null) {
            String metaContent = trimBlockContent(metaBlock.content);
            String metaName = extractValue(metaContent, "name");
            if (metaName != null && !metaName.isBlank()) {
                req.name = metaName.trim();
                req.path = folderPath == null || folderPath.isEmpty() ? req.name : folderPath + "/" + req.name;
            }
            String seq = extractValue(metaContent, "seq");
            if (seq != null && !seq.isBlank()) {
                try {
                    req.sequenceOrder = Integer.parseInt(seq.trim());
                } catch (NumberFormatException ignored) {
                    // Keep the file order when seq is malformed.
                }
            }
        }

        if (methodBlockContent != null) {
            String inlineUrl = extractValue(methodBlockContent, "url");
            if (inlineUrl != null && !inlineUrl.isBlank()) {
                req.url = inlineUrl.trim();
            }
        }

        MethodSelectors selectors = parseMethodSelectors(methodBlockContent);
        List<ApiRequest.Header> headers = parseHeadersBlock(BrunoBlockScanner.firstByName(requestBlocks, "headers"));
        req.headers.addAll(headers);

        parseBodyBlocks(req, requestBlocks, selectors.bodyMode, collection, displayPath);
        parseRequestAuth(req, requestBlocks, content, selectors.authMode, collection, displayPath);
        parseRequestVariables(req, requestBlocks);
        parseScripts(req, file, requestBlocks);

        if (req.body == null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "none";
        } else if (req.body.mode == null || req.body.mode.isBlank()) {
            req.body.mode = "none";
        }

        return req;
    }

    private boolean looksLikeRequestBru(String content) {
        return looksLikeRequestBru(BrunoBlockScanner.scan(content), content);
    }

    private boolean looksLikeRequestBru(List<BrunoBlockScanner.Block> blocks, String content) {
        return extractBrunoMethod(blocks, content) != null;
    }

    private BrunoMethodMatch extractBrunoMethod(String content) {
        return extractBrunoMethod(BrunoBlockScanner.scan(content), content);
    }

    private BrunoMethodMatch extractBrunoMethod(List<BrunoBlockScanner.Block> blocks, String content) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null) {
                continue;
            }
            String normalized = block.normalizedName();
            if (isStandardHttpMethod(normalized)) {
                return new BrunoMethodMatch(normalized.toUpperCase(Locale.ROOT), block.inlineText);
            }
        }

        if (!hasHttpMeta(blocks, content)) {
            return null;
        }

        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null) {
                continue;
            }
            String methodToken = block.name.trim();
            if (methodToken.isEmpty() || BRUNO_RESERVED_TOKENS.contains(methodToken.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (methodToken.contains(":")) {
                continue;
            }
            return new BrunoMethodMatch(methodToken.toUpperCase(Locale.ROOT), block.inlineText);
        }
        return null;
    }

    private boolean hasHttpMeta(List<BrunoBlockScanner.Block> blocks, String content) {
        BrunoBlockScanner.Block metaBlock = BrunoBlockScanner.firstByName(blocks, "meta");
        if (metaBlock == null) {
            return false;
        }
        String metaContent = trimBlockContent(metaBlock.content);
        String type = extractValue(metaContent, "type");
        return type != null && "http".equalsIgnoreCase(type.trim());
    }

    private String normalizeBrunoMetadataPath(Path root, Path parent) {
        if (root == null || parent == null) {
            return "";
        }
        try {
            Path relative = root.relativize(parent);
            String value = relative.toString().replace(File.separatorChar, '/').trim();
            if (".".equals(value)) {
                return "";
            }
            return AuthInheritanceResolver.normalizeFolderPath(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeRelativeImportPath(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        try {
            Path relative = root.relativize(file);
            return relative.toString().replace(File.separatorChar, '/').trim();
        } catch (Exception ignored) {
            return file.getFileName() != null ? file.getFileName().toString() : "";
        }
    }

    private String singleFileDisplayPath(File file) {
        if (file == null) {
            return "unknown.bru";
        }
        String name = file.getName();
        return name != null && !name.isBlank() ? name : "unknown.bru";
    }

    private List<BrunoBlockScanner.Block> filterBlocksAfterMalformed(List<BrunoBlockScanner.Block> blocks, List<Integer> malformedStartIndices) {
        if (blocks == null || blocks.isEmpty() || malformedStartIndices == null || malformedStartIndices.isEmpty()) {
            return blocks != null ? blocks : Collections.emptyList();
        }
        int lastMalformedStart = malformedStartIndices.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(Integer.MIN_VALUE);
        if (lastMalformedStart == Integer.MIN_VALUE) {
            return blocks;
        }
        List<BrunoBlockScanner.Block> filtered = new ArrayList<>();
        for (BrunoBlockScanner.Block block : blocks) {
            if (block != null && block.startIndex > lastMalformedStart) {
                filtered.add(block);
            }
        }
        return filtered;
    }

    private Map<String, String> parseVarsBlocks(List<BrunoBlockScanner.Block> blocks) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (blocks == null || blocks.isEmpty()) {
            return vars;
        }
        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null || !"vars".equalsIgnoreCase(block.name.trim())) {
                continue;
            }
            vars.putAll(parseVarsBlockContent(block.content));
        }
        return vars;
    }

    private Map<String, String> parseVarsBlockContent(String content) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return vars;
        }
        for (String rawLine : content.split("\\R")) {
            DictionaryEntry entry = parseDictionaryEntry(rawLine);
            if (entry == null || entry.key == null || entry.key.isBlank()) {
                continue;
            }
            vars.put(entry.key, entry.value);
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

    private void applyScopedMetadataAuth(ApiCollection collection, String folderPath, ApiRequest.Auth auth) {
        if (collection == null || auth == null) {
            return;
        }
        String normalizedPath = AuthInheritanceResolver.normalizeFolderPath(folderPath);
        if (normalizedPath.isEmpty()) {
            AuthInheritanceResolver.setCollectionAuth(collection, auth);
        } else {
            AuthInheritanceResolver.setFolderAuth(collection, normalizedPath, AuthInheritanceResolver.normalizeParsedAuthMode(auth), auth);
        }
    }

    private void parseRequestAuth(ApiRequest req,
                                  List<BrunoBlockScanner.Block> blocks,
                                  String content,
                                  String selectedAuthMode,
                                  ApiCollection collection,
                                  String displayPath) throws IOException {
        if (req == null) {
            return;
        }

        String normalizedSelector = normalizeAuthSelector(selectedAuthMode);
        if (normalizedSelector != null) {
            if ("none".equals(normalizedSelector)) {
                AuthInheritanceResolver.markRequestNoAuth(req);
                return;
            }

            if (!SUPPORTED_AUTH_SELECTORS.contains(normalizedSelector)) {
                ApiRequest.Auth unsupportedAuth = emptyAuthOverride(normalizedSelector);
                AuthInheritanceResolver.markRequestExplicitAuth(req, unsupportedAuth);
                recordImportWarning(collection, displayPath,
                        "Unsupported Bruno auth mode '" + normalizedSelector + "' was ignored; imported an explicit empty auth override.", false);
                return;
            }

            BrunoBlockScanner.Block selectedBlock = selectTypedAuthBlock(blocks, normalizedSelector);
            if (selectedBlock != null) {
                ApiRequest.Auth parsedAuth = parseAuthBlock(selectedBlock.name, selectedBlock.content);
                if (parsedAuth == null) {
                    parsedAuth = emptyAuthOverride(normalizedSelector);
                }
                AuthInheritanceResolver.markRequestExplicitAuth(req, parsedAuth);
                return;
            }

            ApiRequest.Auth missingAuth = emptyAuthOverride(normalizedSelector);
            AuthInheritanceResolver.markRequestExplicitAuth(req, missingAuth);
            recordImportWarning(collection, displayPath,
                    "Selected Bruno auth mode '" + normalizedSelector + "' was not found; imported an explicit empty auth override.", false);
            return;
        }

        ApiRequest.Auth parsedAuth = parseAuthFromBlocks(blocks, content);
        if (parsedAuth == null) {
            AuthInheritanceResolver.markRequestInherit(req);
            return;
        }
        if ("none".equalsIgnoreCase(AuthInheritanceResolver.normalizeParsedAuthMode(parsedAuth))) {
            AuthInheritanceResolver.markRequestNoAuth(req);
            return;
        }
        AuthInheritanceResolver.markRequestExplicitAuth(req, parsedAuth);
    }

    private ApiRequest.Auth parseAuthFromBlocks(List<BrunoBlockScanner.Block> blocks, String content) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        BrunoBlockScanner.Block typed = BrunoBlockScanner.firstByName(blocks,
                "auth:none",
                "auth:noauth",
                "auth:no_auth",
                "auth:basic",
                "auth:bearer",
                "auth:apikey",
                "auth:oauth2");
        if (typed != null) {
            return parseAuthBlock(typed.name, typed.content);
        }

        BrunoBlockScanner.Block authBlock = BrunoBlockScanner.firstByName(blocks, "auth");
        if (authBlock != null) {
            return parseAuthBlock(authBlock.name, authBlock.content);
        }
        return null;
    }

    private ApiRequest.Auth parseAuthBlock(String blockName, String blockContent) {
        String normalizedBlockName = blockName != null ? blockName.trim().toLowerCase(Locale.ROOT) : "";
        String content = trimBlockContent(blockContent);

        switch (normalizedBlockName) {
            case "auth:none":
            case "auth:noauth":
            case "auth:no_auth":
                return noneAuth();
            case "auth:basic":
                return basicAuth(content);
            case "auth:bearer":
                return bearerAuth(content);
            case "auth:apikey":
                return apiKeyAuth(content);
            case "auth:oauth2":
                return oauth2Auth(content);
            case "auth":
                return parseLegacyAuth(content);
            default:
                return parseLegacyAuth(content);
        }
    }

    private ApiRequest.Auth parseLegacyAuth(String authContent) {
        if (authContent == null || authContent.isBlank()) {
            return null;
        }
        String flatMode = extractValue(authContent, "mode");
        if (flatMode == null) {
            flatMode = extractValue(authContent, "type");
        }
        if (flatMode != null) {
            String normalized = flatMode.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "none", "noauth", "no_auth" -> noneAuth();
                case "basic" -> basicAuth(authContent);
                case "bearer" -> bearerAuth(authContent);
                case "apikey" -> apiKeyAuth(authContent);
                case "oauth2" -> oauth2Auth(authContent);
                default -> null;
            };
        }
        BrunoBlockScanner.Block nested = BrunoBlockScanner.firstByName(BrunoBlockScanner.scan(authContent),
                "none", "noauth", "no_auth", "basic", "bearer", "apikey", "oauth2");
        if (nested == null) {
            return null;
        }
        return parseAuthBlock(nested.name, nested.content);
    }

    private BrunoBlockScanner.Block selectTypedAuthBlock(List<BrunoBlockScanner.Block> blocks, String normalizedSelector) {
        if (blocks == null || blocks.isEmpty() || normalizedSelector == null || normalizedSelector.isBlank()) {
            return null;
        }
        return switch (normalizedSelector) {
            case "basic" -> BrunoBlockScanner.firstByName(blocks, "auth:basic");
            case "bearer" -> BrunoBlockScanner.firstByName(blocks, "auth:bearer");
            case "apikey" -> BrunoBlockScanner.firstByName(blocks, "auth:apikey");
            case "oauth2" -> BrunoBlockScanner.firstByName(blocks, "auth:oauth2");
            default -> null;
        };
    }

    private MethodSelectors parseMethodSelectors(String content) {
        MethodSelectors selectors = new MethodSelectors();
        if (content == null || content.isBlank()) {
            return selectors;
        }
        selectors.bodyMode = normalizeSelectorValue(extractMethodSelector(content, "body"));
        selectors.authMode = normalizeSelectorValue(extractMethodSelector(content, "auth"));
        return selectors;
    }

    private String extractMethodSelector(String content, String key) {
        if (content == null || content.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            if (line.indexOf('{') >= 0 || line.indexOf('}') >= 0) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String prefix = line.substring(0, colon).trim();
            if (!prefix.equalsIgnoreCase(key)) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                return null;
            }
            return value;
        }
        return null;
    }

    private String normalizeSelectorValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))
                || (normalized.startsWith("`") && normalized.endsWith("`"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeBodySelector(String selector) {
        String normalized = normalizeSelectorValue(selector);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "none", "json", "text", "xml", "graphql", "form-urlencoded", "multipart-form" -> normalized;
            default -> normalized;
        };
    }

    private String normalizeAuthSelector(String selector) {
        String normalized = normalizeSelectorValue(selector);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "none", "noauth", "no_auth" -> "none";
            case "basic", "bearer", "apikey", "oauth2" -> normalized;
            default -> normalized;
        };
    }

    private ApiRequest.Auth emptyAuthOverride(String normalizedSelector) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = normalizedSelector;
        return auth;
    }

    private void recordMalformedBrunoScanWarnings(ApiCollection collection,
                                                  Path rootPath,
                                                  Path filePath,
                                                  List<String> malformedBlocks,
                                                  boolean countAsSkipped) {
        if (collection == null || malformedBlocks == null || malformedBlocks.isEmpty()) {
            return;
        }
        String relativePath = normalizeRelativeImportPath(rootPath, filePath);
        if (relativePath == null || relativePath.isBlank()) {
            relativePath = filePath != null && filePath.getFileName() != null ? filePath.getFileName().toString() : "unknown.bru";
        }
        for (String malformedBlock : malformedBlocks) {
            String reason = malformedBlock != null && !malformedBlock.isBlank()
                    ? "Unclosed Bruno block: " + malformedBlock
                    : "Unclosed Bruno block";
            recordImportWarning(collection, relativePath, reason, countAsSkipped);
        }
    }

    private void recordImportWarning(ApiCollection collection, String relativePath, String reason, boolean countAsSkipped) {
        if (collection == null || reason == null || reason.isBlank()) {
            return;
        }
        String safePath = relativePath != null && !relativePath.isBlank() ? relativePath : "unknown.bru";
        String sanitizedReason = burp.diagnostics.DiagnosticSanitizer.sanitizeText(reason.trim());
        String message = "Bruno import warning for \"" + safePath + "\": " + sanitizedReason;
        if (collection.importWarnings == null) {
            collection.importWarnings = new ArrayList<>();
        }
        collection.importWarnings.add(message);
        if (countAsSkipped) {
            collection.skippedRequestCount++;
        }
        DiagnosticStore.getInstance().record(
                DiagnosticEvent.of(
                        DiagnosticOperation.IMPORT,
                        DiagnosticSeverity.WARNING,
                        "BrunoParser",
                        "Bruno import warning")
                        .withAttribute("path", safePath)
                        .withAttribute("reason", sanitizedReason)
                        .withDetails(message)
        );
    }

    private ApiRequest.Auth noneAuth() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "none";
        return auth;
    }

    private ApiRequest.Auth basicAuth(String content) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "basic";
        auth.properties.put("username", firstNonBlank(extractValue(content, "username"), extractValue(content, "user")));
        auth.properties.put("password", firstNonBlank(extractValue(content, "password"), extractValue(content, "pass")));
        return auth;
    }

    private ApiRequest.Auth bearerAuth(String content) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "bearer";
        auth.properties.put("token", firstNonBlank(extractValue(content, "token"), extractValue(content, "value"), extractValue(content, "access_token")));
        return auth;
    }

    private ApiRequest.Auth apiKeyAuth(String content) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "apikey";
        auth.properties.put("key", firstNonBlank(extractValue(content, "key"), extractValue(content, "name"), extractValue(content, "keyname")));
        auth.properties.put("value", firstNonBlank(extractValue(content, "value"), extractValue(content, "keyvalue"), extractValue(content, "token")));
        String placement = firstNonBlank(extractValue(content, "in"), extractValue(content, "placement"), extractValue(content, "location"));
        auth.properties.put("in", placement != null ? placement : "header");
        return auth;
    }

    private ApiRequest.Auth oauth2Auth(String content) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "oauth2";
        String grantType = firstNonBlank(extractValue(content, "grant_type"), extractValue(content, "grantType"), extractValue(content, "grant"));
        auth.properties.put("grantType", grantType != null ? grantType : "client_credentials");
        auth.properties.put("accessTokenUrl", firstNonBlank(extractValue(content, "access_token_url"), extractValue(content, "accessTokenUrl")));
        auth.properties.put("authorizationUrl", firstNonBlank(extractValue(content, "authorization_url"), extractValue(content, "authorizationUrl")));
        auth.properties.put("clientId", firstNonBlank(extractValue(content, "client_id"), extractValue(content, "clientId")));
        auth.properties.put("clientSecret", firstNonBlank(extractValue(content, "client_secret"), extractValue(content, "clientSecret")));
        auth.properties.put("scope", extractValue(content, "scope"));
        auth.properties.put("accessToken", firstNonBlank(extractValue(content, "access_token"), extractValue(content, "accessToken"), extractValue(content, "token")));
        return auth;
    }

    private DictionaryEntry parseDictionaryEntry(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
            return null;
        }
        boolean disabled = false;
        if (line.startsWith("~")) {
            disabled = true;
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return null;
        }

        int colonIndex;
        String key;
        if (line.startsWith("\"")) {
            StringBuilder keyBuilder = new StringBuilder();
            boolean escaped = false;
            int i = 1;
            for (; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (escaped) {
                    keyBuilder.append(ch);
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    break;
                }
                keyBuilder.append(ch);
            }
            if (i >= line.length()) {
                return null;
            }
            colonIndex = i + 1;
            while (colonIndex < line.length() && Character.isWhitespace(line.charAt(colonIndex))) {
                colonIndex++;
            }
            if (colonIndex >= line.length() || line.charAt(colonIndex) != ':') {
                return null;
            }
            key = keyBuilder.toString();
        } else {
            colonIndex = line.indexOf(':');
            if (colonIndex <= 0) {
                return null;
            }
            key = line.substring(0, colonIndex).trim();
        }

        if (key == null || key.isBlank()) {
            return null;
        }
        String value = line.substring(colonIndex + 1).trim();
        return new DictionaryEntry(key, value, disabled);
    }

    private List<ApiRequest.Header> parseHeadersBlock(BrunoBlockScanner.Block headersBlock) {
        List<ApiRequest.Header> headers = new ArrayList<>();
        if (headersBlock == null || headersBlock.content == null) {
            return headers;
        }
        for (String rawLine : headersBlock.content.split("\\R", -1)) {
            DictionaryEntry entry = parseDictionaryEntry(rawLine);
            if (entry == null || entry.key == null || entry.key.isBlank()) {
                continue;
            }
            headers.add(new ApiRequest.Header(entry.key, entry.value, entry.disabled));
        }
        return headers;
    }

    private void parseBodyBlocks(ApiRequest req,
                                 List<BrunoBlockScanner.Block> blocks,
                                 String selectedBodyMode,
                                 ApiCollection collection,
                                 String displayPath) throws IOException {
        if (req == null || blocks == null || blocks.isEmpty()) {
            return;
        }

        String normalizedSelector = normalizeBodySelector(selectedBodyMode);
        if (normalizedSelector != null) {
            if (!SUPPORTED_BODY_SELECTORS.contains(normalizedSelector)) {
                req.body = new ApiRequest.Body();
                req.body.mode = "none";
                recordImportWarning(collection, displayPath,
                        "Unsupported Bruno body mode '" + normalizedSelector + "' was ignored; imported no body.", false);
                return;
            }

            if ("none".equals(normalizedSelector)) {
                req.body = new ApiRequest.Body();
                req.body.mode = "none";
                return;
            }

            switch (normalizedSelector) {
                case "graphql" -> {
                    BrunoBlockScanner.Block graphqlBlock = BrunoBlockScanner.firstByName(blocks, "body:graphql");
                    BrunoBlockScanner.Block graphqlVarsBlock = BrunoBlockScanner.firstByName(blocks, "body:graphql:vars");
                    req.body = new ApiRequest.Body();
                    req.body.mode = "graphql";
                    req.body.graphql = new ApiRequest.Body.GraphQL();
                    req.body.graphql.query = graphqlBlock != null ? trimBlockContent(graphqlBlock.content) : "";
                    req.body.graphql.variables = graphqlVarsBlock != null && !graphqlVarsBlock.content.isBlank()
                            ? trimBlockContent(graphqlVarsBlock.content)
                            : "{}";
                    req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
                    if (req.body.contentType == null || req.body.contentType.isBlank()) {
                        req.body.contentType = "application/json";
                    }
                    if (graphqlBlock == null) {
                        recordImportWarning(collection, displayPath,
                                "Selected Bruno body mode 'graphql' was not found; imported an empty GraphQL body.", false);
                    }
                    return;
                }
                case "form-urlencoded" -> {
                    BrunoBlockScanner.Block urlencodedBlock = BrunoBlockScanner.firstByName(blocks, "body:form-urlencoded");
                    req.body = new ApiRequest.Body();
                    req.body.mode = "urlencoded";
                    req.body.urlencoded = urlencodedBlock != null ? parseFormFields(urlencodedBlock.content, false) : new ArrayList<>();
                    req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
                    if (req.body.contentType == null || req.body.contentType.isBlank()) {
                        req.body.contentType = "application/x-www-form-urlencoded";
                    }
                    if (urlencodedBlock == null) {
                        recordImportWarning(collection, displayPath,
                                "Selected Bruno body mode 'form-urlencoded' was not found; imported an empty urlencoded body.", false);
                    }
                    return;
                }
                case "multipart-form" -> {
                    BrunoBlockScanner.Block multipartBlock = BrunoBlockScanner.firstByName(blocks, "body:multipart-form");
                    req.body = new ApiRequest.Body();
                    req.body.mode = "formdata";
                    req.body.formdata = multipartBlock != null ? parseFormFields(multipartBlock.content, true) : new ArrayList<>();
                    req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
                    if (req.body.contentType == null || req.body.contentType.isBlank()) {
                        req.body.contentType = "multipart/form-data";
                    }
                    if (multipartBlock == null) {
                        recordImportWarning(collection, displayPath,
                                "Selected Bruno body mode 'multipart-form' was not found; imported an empty multipart body.", false);
                    }
                    return;
                }
                case "json", "text", "xml" -> {
                    String expectedContentType = switch (normalizedSelector) {
                        case "json" -> "application/json";
                        case "xml" -> "application/xml";
                        default -> "text/plain";
                    };
                    BrunoBlockScanner.Block selectedBlock = BrunoBlockScanner.firstByName(blocks,
                            "body:" + normalizedSelector);
                    req.body = new ApiRequest.Body();
                    req.body.mode = "raw";
                    req.body.raw = selectedBlock != null ? trimBlockContent(selectedBlock.content) : "";
                    req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
                    if (req.body.contentType == null || req.body.contentType.isBlank()) {
                        req.body.contentType = expectedContentType;
                    }
                    if (selectedBlock == null) {
                        recordImportWarning(collection, displayPath,
                                "Selected Bruno body mode '" + normalizedSelector + "' was not found; imported an empty " + normalizedSelector + " body.", false);
                    }
                    return;
                }
                default -> {
                    req.body = new ApiRequest.Body();
                    req.body.mode = "none";
                    recordImportWarning(collection, displayPath,
                            "Unsupported Bruno body mode '" + normalizedSelector + "' was ignored; imported no body.", false);
                    return;
                }
            }
        }

        BrunoBlockScanner.Block noneBlock = BrunoBlockScanner.firstByName(blocks, "body:none");
        if (noneBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "none";
            return;
        }

        BrunoBlockScanner.Block graphqlBlock = BrunoBlockScanner.firstByName(blocks, "body:graphql");
        BrunoBlockScanner.Block graphqlVarsBlock = BrunoBlockScanner.firstByName(blocks, "body:graphql:vars");
        if (graphqlBlock != null || graphqlVarsBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "graphql";
            req.body.graphql = new ApiRequest.Body.GraphQL();
            req.body.graphql.query = graphqlBlock != null ? trimBlockContent(graphqlBlock.content) : "";
            req.body.graphql.variables = graphqlVarsBlock != null && !graphqlVarsBlock.content.isBlank()
                    ? trimBlockContent(graphqlVarsBlock.content)
                    : "{}";
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/json";
            }
            return;
        }

        BrunoBlockScanner.Block multipartBlock = BrunoBlockScanner.firstByName(blocks, "body:multipart-form");
        if (multipartBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "formdata";
            req.body.formdata = parseFormFields(multipartBlock.content, true);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            return;
        }

        BrunoBlockScanner.Block urlencodedBlock = BrunoBlockScanner.firstByName(blocks, "body:form-urlencoded");
        if (urlencodedBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "urlencoded";
            req.body.urlencoded = parseFormFields(urlencodedBlock.content, false);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/x-www-form-urlencoded";
            }
            return;
        }

        BrunoBlockScanner.Block jsonBlock = BrunoBlockScanner.firstByName(blocks, "body:json");
        if (jsonBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "raw";
            req.body.raw = trimBlockContent(jsonBlock.content);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/json";
            }
            return;
        }

        BrunoBlockScanner.Block textBlock = BrunoBlockScanner.firstByName(blocks, "body:text");
        if (textBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "raw";
            req.body.raw = trimBlockContent(textBlock.content);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "text/plain";
            }
            return;
        }

        BrunoBlockScanner.Block xmlBlock = BrunoBlockScanner.firstByName(blocks, "body:xml");
        if (xmlBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "raw";
            req.body.raw = trimBlockContent(xmlBlock.content);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/xml";
            }
            return;
        }

        BrunoBlockScanner.Block legacyBodyBlock = BrunoBlockScanner.firstByName(blocks, "body");
        if (legacyBodyBlock != null) {
            String raw = trimBlockContent(legacyBodyBlock.content);
            if (raw != null && !raw.isBlank()) {
                req.body = new ApiRequest.Body();
                req.body.mode = "raw";
                req.body.raw = raw;
                req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
                if (req.body.contentType == null || req.body.contentType.isBlank()) {
                    req.body.contentType = looksLikeJsonBody(raw) ? "application/json" : "text/plain";
                }
            }
        }
    }

    private List<ApiRequest.Body.FormField> parseFormFields(String content, boolean multipart) throws IOException {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return fields;
        }

        StringBuilder currentBuffer = new StringBuilder();
        String currentKey = null;
        boolean currentDisabled = false;
        boolean currentFileUpload = false;
        BraceState currentState = new BraceState(0, false, false, false);

        for (String rawLine : content.split("\\R", -1)) {
            DictionaryEntry entry = parseDictionaryEntry(rawLine);
            boolean startOfField = entry != null && (currentKey == null || currentState.depth == 0);

            if (startOfField) {
                if (currentKey != null) {
                    fields.add(createFormField(currentKey, currentBuffer.toString(), currentDisabled, currentFileUpload, multipart));
                }
                currentKey = null;
                currentBuffer.setLength(0);
                currentState = new BraceState(0, false, false, false);
                currentDisabled = entry.disabled;
                currentKey = entry.key;
                String initialValue = entry.value != null ? entry.value : "";
                currentBuffer.append(initialValue);
                currentState = updateBraceState(initialValue, currentState.inSingleQuote, currentState.inDoubleQuote, currentState.escaped, currentState.depth);
                currentFileUpload = isFileFieldValue(currentBuffer.toString());
                continue;
            }

            if (currentKey == null) {
                continue;
            }

            if (currentBuffer.length() > 0) {
                currentBuffer.append('\n');
            }
            currentBuffer.append(rawLine);
            currentState = updateBraceState(rawLine, currentState.inSingleQuote, currentState.inDoubleQuote, currentState.escaped, currentState.depth);
            currentFileUpload = currentFileUpload || isFileFieldValue(currentBuffer.toString());
        }

        if (currentKey != null) {
            fields.add(createFormField(currentKey, currentBuffer.toString(), currentDisabled, currentFileUpload, multipart));
        }

        return fields;
    }

    private ApiRequest.Body.FormField createFormField(String key, String value, boolean disabled, boolean fileUpload, boolean multipart) {
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value != null ? trimBlockContent(value) : "");
        field.disabled = disabled;
        if (multipart && fileUpload) {
            field.fileUpload = true;
            field.type = "file";
            field.filePath = extractFilePath(field.value);
            if (field.filePath == null || field.filePath.isBlank()) {
                field.filePath = field.value;
            }
        } else {
            field.type = "text";
        }
        return field;
    }

    private boolean isFieldStartLine(String trimmedLine) {
        return parseDictionaryEntry(trimmedLine) != null;
    }

    private boolean isFileFieldValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("@file(") && trimmed.endsWith(")");
    }

    private String extractFilePath(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("@file(") || !trimmed.endsWith(")")) {
            return null;
        }
        return trimmed.substring("@file(".length(), trimmed.length() - 1).trim();
    }

    private String trimBlockContent(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.replace("\r\n", "\n").replace('\r', '\n');
        return trimmed.trim();
    }

    private String firstEnabledHeaderValue(List<ApiRequest.Header> headers, String headerName) {
        if (headers == null || headers.isEmpty() || headerName == null) {
            return null;
        }
        for (ApiRequest.Header header : headers) {
            if (header == null || header.disabled || header.key == null) {
                continue;
            }
            if (headerName.equalsIgnoreCase(header.key.trim())) {
                return header.value;
            }
        }
        return null;
    }

    private boolean looksLikeJsonBody(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private void parseRequestVariables(ApiRequest req, List<BrunoBlockScanner.Block> blocks) {
        if (req == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null || !"vars".equalsIgnoreCase(block.name.trim())) {
                continue;
            }
            vars.putAll(parseVarsBlockContent(block.content));
        }
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            ApiRequest.Variable variable = new ApiRequest.Variable();
            variable.key = entry.getKey();
            variable.value = entry.getValue();
            variable.enabled = true;
            variable.type = "string";
            req.variables.add(variable);
        }
    }

    private void parseScripts(ApiRequest req, File file, List<BrunoBlockScanner.Block> blocks) {
        if (req == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        int order = 0;
        BrunoBlockScanner.Block assertBlock = BrunoBlockScanner.firstByName(blocks, "assert", "test", "tests", "body:test");
        if (assertBlock != null && assertBlock.content != null) {
            ApiRequest.Script script = new ApiRequest.Script("js", trimBlockContent(assertBlock.content));
            req.postResponseScripts.add(script);
            addScriptBlock(req, script, ScriptDialect.BRUNO, ScriptPhase.TEST, file, order++);
        }

        BrunoBlockScanner.Block preScriptBlock = BrunoBlockScanner.firstByName(blocks, "script:pre-request");
        if (preScriptBlock != null && preScriptBlock.content != null) {
            ApiRequest.Script script = new ApiRequest.Script("js", trimBlockContent(preScriptBlock.content));
            req.preRequestScripts.add(script);
            addScriptBlock(req, script, ScriptDialect.BRUNO, ScriptPhase.PRE_REQUEST, file, order++);
        }

        BrunoBlockScanner.Block postScriptBlock = BrunoBlockScanner.firstByName(blocks, "script:post-response");
        if (postScriptBlock != null && postScriptBlock.content != null) {
            ApiRequest.Script script = new ApiRequest.Script("js", trimBlockContent(postScriptBlock.content));
            req.postResponseScripts.add(script);
            addScriptBlock(req, script, ScriptDialect.BRUNO, ScriptPhase.POST_RESPONSE, file, order);
        }
    }

    private void sortRequestsBySequence(ApiCollection collection) {
        if (collection == null || collection.requests == null || collection.requests.size() < 2) {
            return;
        }
        collection.requests.sort(Comparator
                .comparingInt((ApiRequest request) -> request != null ? request.sequenceOrder : Integer.MAX_VALUE)
                .thenComparing(request -> request != null && request.path != null ? request.path.toLowerCase(Locale.ROOT) : ""));
    }

    private void recordMalformedBrunoFile(ApiCollection collection, Path rootPath, Path filePath, Exception exception) {
        if (collection == null) {
            return;
        }
        String relativePath = normalizeRelativeImportPath(rootPath, filePath);
        if (relativePath == null || relativePath.isBlank()) {
            relativePath = filePath != null && filePath.getFileName() != null ? filePath.getFileName().toString() : "unknown.bru";
        }
        String reason = exception != null ? exception.getMessage() : "malformed Bruno request";
        reason = reason != null ? reason.trim() : "malformed Bruno request";
        if (reason.isEmpty()) {
            reason = exception != null ? exception.getClass().getSimpleName() : "malformed Bruno request";
        }
        String sanitizedReason = burp.diagnostics.DiagnosticSanitizer.sanitizeText(reason);
        String message = "Skipped malformed Bruno file \"" + relativePath + "\": " + sanitizedReason;
        if (collection.importWarnings == null) {
            collection.importWarnings = new ArrayList<>();
        }
        collection.importWarnings.add(message);
        collection.skippedRequestCount++;
        DiagnosticStore.getInstance().record(
                DiagnosticEvent.of(
                        DiagnosticOperation.IMPORT,
                        DiagnosticSeverity.WARNING,
                        "BrunoParser",
                        "Malformed Bruno file skipped")
                        .withAttribute("path", relativePath)
                        .withAttribute("reason", sanitizedReason)
                        .withDetails(message)
        );
    }

    private String methodBlockContentForUrl(List<BrunoBlockScanner.Block> blocks, String content, BrunoMethodMatch methodMatch) {
        if (blocks == null || methodMatch == null) {
            return null;
        }
        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null) {
                continue;
            }
            String normalized = block.normalizedName();
            if (normalized.equals(methodMatch.method.toLowerCase(Locale.ROOT))) {
                return trimBlockContent(block.content);
            }
        }
        return null;
    }

    private boolean isStandardHttpMethod(String methodToken) {
        if (methodToken == null || methodToken.isBlank()) {
            return false;
        }
        return switch (methodToken.toLowerCase(Locale.ROOT)) {
            case "get", "post", "put", "delete", "patch", "head", "options", "trace", "connect" -> true;
            default -> false;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stripBrunoExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "request";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bru")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private String extractValue(String block, String key) {
        if (block == null || key == null || key.isBlank()) {
            return null;
        }
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(block);
        if (m.find()) {
            String val = m.group(1).trim();
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            return val.isEmpty() ? null : val;
        }
        return null;
    }

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

    private static final class BrunoMethodMatch {
        private final String method;
        private final String sameLineUrl;

        private BrunoMethodMatch(String method, String sameLineUrl) {
            this.method = method;
            this.sameLineUrl = sameLineUrl;
        }
    }

    private static final class MethodSelectors {
        private String bodyMode;
        private String authMode;
    }

    private static final class DictionaryEntry {
        private final String key;
        private final String value;
        private final boolean disabled;

        private DictionaryEntry(String key, String value, boolean disabled) {
            this.key = key;
            this.value = value;
            this.disabled = disabled;
        }
    }

    private static final class BraceState {
        private final int depth;
        private final boolean inSingleQuote;
        private final boolean inDoubleQuote;
        private final boolean escaped;

        private BraceState(int depth, boolean inSingleQuote, boolean inDoubleQuote, boolean escaped) {
            this.depth = depth;
            this.inSingleQuote = inSingleQuote;
            this.inDoubleQuote = inDoubleQuote;
            this.escaped = escaped;
        }
    }

    private BraceState updateBraceState(String text, boolean inSingleQuote, boolean inDoubleQuote, boolean escaped, int depth) {
        if (text == null) {
            return new BraceState(depth, inSingleQuote, inDoubleQuote, escaped);
        }
        int currentDepth = depth;
        boolean single = inSingleQuote;
        boolean dbl = inDoubleQuote;
        boolean esc = escaped;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (ch == '\\' && (single || dbl)) {
                esc = true;
                continue;
            }
            if (single) {
                if (ch == '\'') {
                    single = false;
                }
                continue;
            }
            if (dbl) {
                if (ch == '"') {
                    dbl = false;
                }
                continue;
            }
            if (ch == '\'') {
                single = true;
                continue;
            }
            if (ch == '"') {
                dbl = true;
                continue;
            }
            if (ch == '{' || ch == '[' || ch == '(') {
                currentDepth++;
            } else if (ch == '}' || ch == ']' || ch == ')') {
                currentDepth = Math.max(0, currentDepth - 1);
            }
        }
        return new BraceState(currentDepth, single, dbl, esc);
    }

    @Override
    public String getFormatName() { return "Bruno"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"bru", "zip"}; }
}
