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
import burp.utils.RequestParameterSupport;
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
    private final ArchiveImportLimits archiveImportLimits;
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
            "none", "json", "text", "xml", "graphql", "form-urlencoded", "multipart-form", "file");
    private static final Set<String> SUPPORTED_AUTH_SELECTORS = Set.of(
            "none", "noauth", "no_auth", "basic", "bearer", "apikey", "oauth2");

    public BrunoParser() {
        this(ArchiveImportLimits.defaultLimits());
    }

    public BrunoParser(ArchiveImportLimits archiveImportLimits) {
        this.archiveImportLimits = ArchiveImportLimits.copyOf(archiveImportLimits);
        this.archiveImportLimits.normalize();
    }

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
            if (isZipFile(file)) {
                extractedZipRoot = createTemporaryExtractionDirectory();
                SafeArchiveExtractor.extract(file.toPath(), extractedZipRoot, archiveImportLimits);
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
                        ApiRequest req = parseBruFile(file, "", content, effectiveBlocks, collection,
                                singleFileDisplayPath(file), false);
                        if (req != null) {
                            req.sourceCollection = collection.name;
                            collection.requests.add(req);
                            collection.importedRequestCount++;
                        }
                    } else {
                        if (containsLegacyQuotedDictionaryValue(content)) {
                            recordImportWarning(collection, singleFileDisplayPath(file),
                                    "Ambiguous quoted dictionary values were preserved literally because no legacy collection metadata was present.", false);
                        }
                        putCollectionVariables(collection, parseVarsEntries(scanResult.blocks));
                        ApiRequest.Auth auth = parseAuthFromBlocks(scanResult.blocks, content);
                        if (auth != null) {
                            applyScopedMetadataAuth(collection, "", auth);
                        }
                        warnMissingScopedAuthBlock(collection, singleFileDisplayPath(file), scanResult.blocks);
                        importScopedScripts(collection, "", effectiveBlocks, singleFileDisplayPath(file));
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

    private boolean looksLikeBrunoZip(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            ZipEntry entry;
            int inspected = 0;
            while ((entry = zin.getNextEntry()) != null) {
                inspected++;
                if (inspected > Math.max(1, archiveImportLimits.maxEntries)) {
                    throw new ArchiveImportLimitException(
                            ArchiveImportLimitException.Reason.ENTRY_COUNT,
                            entry.getName(),
                            inspected,
                            archiveImportLimits.maxEntries
                    );
                }
                String entryName;
                try {
                    entryName = SafeArchiveExtractor.normalizeEntryName(entry.getName()).toLowerCase(Locale.ROOT);
                } catch (ArchiveImportLimitException e) {
                    throw e;
                }
                if (entryName.endsWith(".bru") || entryName.endsWith("bruno.json")) {
                    return true;
                }
            }
        } catch (ArchiveImportLimitException e) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    Path createTemporaryExtractionDirectory() throws IOException {
        return Files.createTempDirectory("bruno-import-");
    }

    private boolean isZipFile(File file) {
        return file != null
                && file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
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

        boolean legacyQuotedValues = bruFiles.stream().anyMatch(path -> {
            String name = path.getFileName() != null ? path.getFileName().toString() : "";
            return "_collection.bru".equalsIgnoreCase(name) || "_folder.bru".equalsIgnoreCase(name);
        });
        for (Path path : bruFiles) {
            try {
                String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                String folderPath = normalizeBrunoMetadataPath(rootPath, path.getParent());
                BrunoBlockScanner.ScanResult scanResult = BrunoBlockScanner.scanDetailed(content);
                recordMalformedBrunoScanWarnings(collection, rootPath, path, scanResult.malformedBlocks, true);
                List<BrunoBlockScanner.Block> effectiveBlocks = filterBlocksAfterMalformed(scanResult.blocks, scanResult.malformedBlockStartIndices);
                String relativePath = normalizeRelativeImportPath(rootPath, path);
                if (relativePath.toLowerCase(Locale.ROOT).startsWith("environments/")) {
                    for (DictionaryEntry entry : parseVarsEntries(scanResult.blocks, legacyQuotedValues)) {
                        if (entry != null && !entry.disabled && entry.key != null) {
                            collection.environment.put(entry.key, entry.value);
                        }
                    }
                } else if (looksLikeRequestBru(effectiveBlocks, content)) {
                    ApiRequest req = parseBruFile(path.toFile(), folderPath, content, effectiveBlocks, collection,
                            normalizeRelativeImportPath(rootPath, path), legacyQuotedValues);
                    if (req != null) {
                        req.sourceCollection = collection.name;
                        collection.requests.add(req);
                        if (collection.importedRequestCount < Integer.MAX_VALUE) {
                            collection.importedRequestCount++;
                        }
                    }
                } else {
                    if (legacyQuotedValues && containsLegacyQuotedDictionaryValue(content)) {
                        recordImportWarning(collection, relativePath,
                                "Legacy quoted dictionary values were decoded using API Workbench compatibility rules; embedded backslash escapes may be ambiguous.", false);
                    }
                    List<DictionaryEntry> vars = parseVarsEntries(scanResult.blocks, legacyQuotedValues);
                    if (!vars.isEmpty()) {
                        if (folderPath.isEmpty()) {
                            putCollectionVariables(collection, vars);
                        } else {
                            putFolderVariables(collection, folderPath, vars, normalizeRelativeImportPath(rootPath, path));
                        }
                    }
                    ApiRequest.Auth folderAuth = parseAuthFromBlocks(scanResult.blocks, content, legacyQuotedValues);
                    if (folderAuth != null) {
                        applyScopedMetadataAuth(collection, folderPath, folderAuth);
                    }
                    warnMissingScopedAuthBlock(collection, relativePath, scanResult.blocks);
                    importScopedScripts(collection, folderPath, effectiveBlocks, relativePath);
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
        return parseBruFile(file, folderPath, content, scanResult.blocks, null,
                file != null ? file.getName() : null, false);
    }

    private ApiRequest parseBruFile(File file, String folderPath, String content,
                                    List<BrunoBlockScanner.Block> blocks, ApiCollection collection,
                                    String displayPath, boolean legacyQuotedValues) throws IOException {
        if (file == null || content == null || blocks == null) {
            return null;
        }
        if (legacyQuotedValues && containsLegacyQuotedDictionaryValue(content)) {
            recordImportWarning(collection, displayPath,
                    "Legacy quoted dictionary values were decoded using API Workbench compatibility rules; embedded backslash escapes may be ambiguous.", false);
        }

        BrunoMethodMatch methodMatch = extractBrunoMethod(blocks, content, legacyQuotedValues);
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
            String metaContent = metaBlock.content;
            String metaName = extractValue(metaContent, "name", legacyQuotedValues);
            if (metaName != null && !metaName.isBlank()) {
                req.name = metaName;
                req.path = folderPath == null || folderPath.isEmpty() ? req.name : folderPath + "/" + req.name;
            }
            String seq = extractValue(metaContent, "seq", legacyQuotedValues);
            if (seq != null && !seq.isBlank()) {
                try {
                    req.sequenceOrder = Integer.parseInt(seq.trim());
                } catch (NumberFormatException ignored) {
                    // Keep the file order when seq is malformed.
                }
            }
        }

        if (methodBlockContent != null) {
            String inlineUrl = extractValue(methodBlockContent, "url", legacyQuotedValues);
            if (inlineUrl != null && !inlineUrl.isBlank()) {
                req.url = inlineUrl;
            }
        }

        MethodSelectors selectors = parseMethodSelectors(methodBlockContent);
        parseRequestParameters(req, requestBlocks, legacyQuotedValues);
        List<ApiRequest.Header> headers = parseHeadersBlock(
                BrunoBlockScanner.firstByName(requestBlocks, "headers"), legacyQuotedValues);
        req.headers.addAll(headers);

        parseBodyBlocks(req, requestBlocks, selectors.bodyMode, collection, displayPath, legacyQuotedValues);
        parseRequestAuth(req, requestBlocks, content, selectors.authMode, collection, displayPath, legacyQuotedValues);
        parseRequestVariables(req, requestBlocks, collection, displayPath, legacyQuotedValues);
        parseScripts(req, file, requestBlocks);

        if (req.body == null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "none";
        } else if (req.body.mode == null || req.body.mode.isBlank()) {
            req.body.mode = "none";
        }

        return req;
    }

    private void parseRequestParameters(ApiRequest request, List<BrunoBlockScanner.Block> blocks,
                                        boolean legacyQuotedValues) {
        if (request == null) {
            return;
        }
        BrunoBlockScanner.Block queryBlock = BrunoBlockScanner.firstByName(blocks, "params:query", "query");
        List<ApiRequest.Parameter> structuredQuery = null;
        if (queryBlock != null) {
            structuredQuery = new ArrayList<>();
            for (DictionaryEntry entry : parseDictionaryEntries(queryBlock.content, legacyQuotedValues)) {
                ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", entry.key, entry.value);
                parameter.valuePresent = true;
                parameter.disabled = entry.disabled;
                parameter.description = entry.description;
                parameter.source = "bruno:params:query";
                structuredQuery.add(parameter);
            }
        }
        request.parameters.addAll(QueryParameterImportSupport.reconcileStructuredQueryWithRawUrl(
                request.url, structuredQuery, "bruno:url.raw", "bruno:url.raw-unmatched"));
        request.url = RequestParameterSupport.materializeUrl(request.url, request.parameters, null);

        BrunoBlockScanner.Block pathBlock = BrunoBlockScanner.firstByName(blocks, "params:path", "path");
        if (pathBlock != null) {
            for (DictionaryEntry entry : parseDictionaryEntries(pathBlock.content, legacyQuotedValues)) {
                ApiRequest.Parameter parameter = new ApiRequest.Parameter("path", entry.key, entry.value);
                parameter.valuePresent = true;
                parameter.disabled = entry.disabled;
                parameter.description = entry.description;
                parameter.source = "bruno:params:path";
                request.parameters.add(parameter);
            }
        }
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
        return extractBrunoMethod(blocks, content, false);
    }

    private BrunoMethodMatch extractBrunoMethod(List<BrunoBlockScanner.Block> blocks, String content,
                                                 boolean legacyQuotedValues) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null) {
                continue;
            }
            String normalized = block.normalizedName();
            if (isStandardHttpMethod(normalized)) {
                return new BrunoMethodMatch(normalized.toUpperCase(Locale.ROOT), block.inlineText, normalized);
            }
        }

        BrunoBlockScanner.Block canonicalHttp = BrunoBlockScanner.firstByName(blocks, "http");
        if (canonicalHttp != null) {
            String customMethod = extractValue(canonicalHttp.content, "method", legacyQuotedValues);
            if (customMethod != null && !customMethod.isBlank()) {
                return new BrunoMethodMatch(customMethod, canonicalHttp.inlineText, "http");
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
            return new BrunoMethodMatch(methodToken.toUpperCase(Locale.ROOT), block.inlineText, methodToken);
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
        for (DictionaryEntry entry : parseVarsEntries(blocks)) {
            if (!entry.disabled) {
                vars.put(entry.key, entry.value);
            }
        }
        return vars;
    }

    private List<DictionaryEntry> parseVarsEntries(List<BrunoBlockScanner.Block> blocks) {
        return parseVarsEntries(blocks, false);
    }

    private List<DictionaryEntry> parseVarsEntries(List<BrunoBlockScanner.Block> blocks,
                                                    boolean legacyQuotedValues) {
        List<DictionaryEntry> entries = new ArrayList<>();
        if (blocks == null) {
            return entries;
        }
        for (BrunoBlockScanner.Block block : blocks) {
            if (block != null && block.name != null
                    && ("vars".equalsIgnoreCase(block.name.trim())
                    || "vars:pre-request".equalsIgnoreCase(block.name.trim())
                    || "vars:post-response".equalsIgnoreCase(block.name.trim()))) {
                entries.addAll(parseDictionaryEntries(block.content, legacyQuotedValues));
            }
        }
        return entries;
    }

    private List<DictionaryEntry> parseDictionaryEntries(String content) {
        return parseDictionaryEntries(content, false);
    }

    private List<DictionaryEntry> parseDictionaryEntries(String content, boolean legacyQuotedValues) {
        List<DictionaryEntry> entries = new ArrayList<>();
        if (content == null) {
            return entries;
        }
        List<String> lines = BrunoSourceSupport.lines(content);
        String pendingType = "string";
        String pendingDescription = null;
        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);
            String trimmed = rawLine.trim();
            if (trimmed.matches("@(string|number|boolean|object)")) {
                pendingType = trimmed.substring(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (trimmed.startsWith("@description(")) {
                DescriptionParse description = parseDescriptionAnnotation(lines, index);
                if (description != null) {
                    pendingDescription = description.value;
                    index = description.lastLine;
                }
                continue;
            }
            DictionaryEntry entry = parseDictionaryEntry(rawLine, legacyQuotedValues);
            if (entry != null) {
                if ("'''".equals(entry.value)) {
                    int entryIndent = leadingPhysicalWhitespace(rawLine);
                    String structuralPrefix = " ".repeat(Math.max(0, entryIndent + 2));
                    boolean canonicalMultiline = index + 1 < lines.size()
                            && (lines.get(index + 1).startsWith(structuralPrefix)
                            || isDictionaryMultilineClose(lines.get(index + 1), entryIndent));
                    if (!canonicalMultiline) {
                        entry = new DictionaryEntry(entry.key, entry.value, entry.disabled,
                                pendingType, pendingDescription, entry.legacyQuoted);
                        entries.add(entry);
                        pendingType = "string";
                        pendingDescription = null;
                        continue;
                    }
                    StringBuilder value = new StringBuilder();
                    boolean closed = false;
                    boolean firstValueLine = true;
                    while (++index < lines.size()) {
                        String valueLine = lines.get(index);
                        if (isDictionaryMultilineClose(valueLine, entryIndent)) {
                            closed = true;
                            break;
                        }
                        if (valueLine.startsWith(structuralPrefix)) {
                            valueLine = valueLine.substring(structuralPrefix.length());
                        }
                        if (!firstValueLine) value.append('\n');
                        value.append(valueLine);
                        firstValueLine = false;
                    }
                    String decodedValue = closed ? value.toString() : "'''";
                    entry = new DictionaryEntry(entry.key, decodedValue, entry.disabled,
                            pendingType, pendingDescription, false);
                } else {
                    entry = new DictionaryEntry(entry.key, entry.value, entry.disabled,
                            pendingType, pendingDescription, entry.legacyQuoted);
                }
                entries.add(entry);
                pendingType = "string";
                pendingDescription = null;
            }
        }
        return entries;
    }

    private DescriptionParse parseDescriptionAnnotation(List<String> lines, int start) {
        String openingLine = lines.get(start);
        String trimmed = openingLine.trim();
        String prefix = "@description(";
        if (!trimmed.startsWith(prefix)) return null;
        String argument = trimmed.substring(prefix.length());
        if (argument.startsWith("'''")) {
            String remainder = argument.substring(3);
            int annotationIndent = leadingPhysicalWhitespace(openingLine);
            String structuralPrefix = " ".repeat(annotationIndent + 2);
            List<String> payload = new ArrayList<>();
            int index = start;
            if (!remainder.isEmpty() && !remainder.equals(")")) payload.add(remainder);
            while (++index < lines.size()) {
                String line = lines.get(index);
                int indent = leadingPhysicalWhitespace(line);
                if (indent == annotationIndent && line.substring(indent).equals("''')")) {
                    return new DescriptionParse(String.join("\n", payload), index);
                }
                int closeIndex = line.indexOf("''')");
                String contentLine = closeIndex >= 0 ? line.substring(0, closeIndex) : line;
                payload.add(contentLine.startsWith(structuralPrefix)
                        ? contentLine.substring(structuralPrefix.length()) : contentLine);
                if (closeIndex >= 0) return new DescriptionParse(String.join("\n", payload), index);
            }
            return new DescriptionParse(String.join("\n", payload), lines.size() - 1);
        }
        if (!argument.endsWith(")")) return null;
        argument = argument.substring(0, argument.length() - 1);
        if (argument.length() >= 2) {
            char quote = argument.charAt(0);
            if ((quote == '\'' || quote == '"') && argument.charAt(argument.length() - 1) == quote) {
                argument = decodeAnnotationQuoted(argument.substring(1, argument.length() - 1), quote);
            }
        }
        return new DescriptionParse(argument, start);
    }

    private String decodeAnnotationQuoted(String value, char quote) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\\' && index + 1 < value.length() && value.charAt(index + 1) == quote) {
                out.append(quote);
                index++;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private boolean isDictionaryMultilineClose(String line, int entryIndent) {
        return line != null
                && leadingPhysicalWhitespace(line) == entryIndent
                && line.trim().equals("'''");
    }

    private int leadingPhysicalWhitespace(String line) {
        int count = 0;
        while (line != null && count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private Map<String, String> parseVarsBlockContent(String content) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return vars;
        }
        for (String rawLine : BrunoSourceSupport.lines(content)) {
            DictionaryEntry entry = parseDictionaryEntry(rawLine);
            if (entry == null || entry.key == null || entry.key.isBlank()) {
                continue;
            }
            vars.put(entry.key, entry.value);
        }
        return vars;
    }

    private void putCollectionVariables(ApiCollection collection, List<DictionaryEntry> vars) {
        if (collection == null || vars == null || vars.isEmpty()) {
            return;
        }
        for (DictionaryEntry entry : vars) {
            if (entry == null || entry.key == null) {
                continue;
            }
            ApiRequest.Variable variable = new ApiRequest.Variable();
            variable.key = entry.key;
            variable.type = entry.type;
            variable.value = entry.value;
            variable.enabled = !entry.disabled;
            collection.variables.add(variable);
            if (!isValidTypedVariable(entry.type, entry.value)) {
                recordImportWarning(collection, "collection.bru",
                        "Variable '" + entry.key + "' retained invalid " + entry.type + " content as text.", false);
            }
        }
    }

    private void putFolderVariables(ApiCollection collection, String folderPath, List<DictionaryEntry> vars,
                                    String displayPath) {
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
        for (DictionaryEntry entry : vars) {
            if (entry == null || entry.key == null) {
                continue;
            }
            if (entry.disabled) {
                recordImportWarning(collection, displayPath,
                        "Disabled folder variable '" + entry.key + "' in folder '" + normalizedPath
                                + "' was not activated.", false);
            } else {
                existing.put(entry.key, entry.value);
            }
        }
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
                                  String displayPath,
                                  boolean legacyQuotedValues) throws IOException {
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
                ApiRequest.Auth parsedAuth = parseAuthBlock(selectedBlock.name, selectedBlock.content, legacyQuotedValues);
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

        ApiRequest.Auth parsedAuth = parseAuthFromBlocks(blocks, content, legacyQuotedValues);
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
        return parseAuthFromBlocks(blocks, content, false);
    }

    private ApiRequest.Auth parseAuthFromBlocks(List<BrunoBlockScanner.Block> blocks, String content,
                                                boolean legacyQuotedValues) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        BrunoBlockScanner.Block authBlock = BrunoBlockScanner.firstByName(blocks, "auth");
        if (authBlock != null) {
            String selected = normalizeAuthSelector(extractValue(authBlock.content, "mode", legacyQuotedValues));
            if (selected != null) {
                if ("none".equals(selected)) return noneAuth();
                BrunoBlockScanner.Block selectedBlock = selectTypedAuthBlock(blocks, selected);
                if (selectedBlock != null) return parseAuthBlock(selectedBlock.name, selectedBlock.content, legacyQuotedValues);
                if (parseDictionaryEntries(authBlock.content, legacyQuotedValues).size() > 1) {
                    return parseAuthBlock("auth:" + selected, authBlock.content, legacyQuotedValues);
                }
                return emptyAuthOverride(selected);
            }
            ApiRequest.Auth legacy = parseAuthBlock(authBlock.name, authBlock.content, legacyQuotedValues);
            if (legacy != null) return legacy;
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
            return parseAuthBlock(typed.name, typed.content, legacyQuotedValues);
        }
        return null;
    }

    private void warnMissingScopedAuthBlock(ApiCollection collection, String displayPath,
                                            List<BrunoBlockScanner.Block> blocks) {
        BrunoBlockScanner.Block selector = BrunoBlockScanner.firstByName(blocks, "auth");
        if (selector == null) return;
        String mode = normalizeAuthSelector(extractValue(selector.content, "mode"));
        if (mode != null && !"none".equals(mode) && SUPPORTED_AUTH_SELECTORS.contains(mode)
                && selectTypedAuthBlock(blocks, mode) == null
                && parseDictionaryEntries(selector.content).size() <= 1) {
            recordImportWarning(collection, displayPath,
                    "Selected Bruno auth mode '" + mode + "' was not found; retained an explicit empty auth override.", false);
        }
    }

    private ApiRequest.Auth parseAuthBlock(String blockName, String blockContent) {
        return parseAuthBlock(blockName, blockContent, false);
    }

    private ApiRequest.Auth parseAuthBlock(String blockName, String blockContent, boolean legacyQuotedValues) {
        String normalizedBlockName = blockName != null ? blockName.trim().toLowerCase(Locale.ROOT) : "";
        String content = blockContent;

        switch (normalizedBlockName) {
            case "auth:none":
            case "auth:noauth":
            case "auth:no_auth":
                return noneAuth();
            case "auth:basic":
                return basicAuth(content, legacyQuotedValues);
            case "auth:bearer":
                return bearerAuth(content, legacyQuotedValues);
            case "auth:apikey":
                return apiKeyAuth(content, legacyQuotedValues);
            case "auth:oauth2":
                return oauth2Auth(content, legacyQuotedValues);
            case "auth":
                return parseLegacyAuth(content, legacyQuotedValues);
            default:
                return parseLegacyAuth(content, legacyQuotedValues);
        }
    }

    private ApiRequest.Auth parseLegacyAuth(String authContent) {
        return parseLegacyAuth(authContent, false);
    }

    private ApiRequest.Auth parseLegacyAuth(String authContent, boolean legacyQuotedValues) {
        if (authContent == null || authContent.isBlank()) {
            return null;
        }
        String flatMode = extractValue(authContent, "mode", legacyQuotedValues);
        if (flatMode == null) {
            flatMode = extractValue(authContent, "type", legacyQuotedValues);
        }
        if (flatMode != null) {
            String normalized = flatMode.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "none", "noauth", "no_auth" -> noneAuth();
                case "basic" -> basicAuth(authContent, legacyQuotedValues);
                case "bearer" -> bearerAuth(authContent, legacyQuotedValues);
                case "apikey" -> apiKeyAuth(authContent, legacyQuotedValues);
                case "oauth2" -> oauth2Auth(authContent, legacyQuotedValues);
                default -> null;
            };
        }
        BrunoBlockScanner.Block nested = BrunoBlockScanner.firstByName(BrunoBlockScanner.scan(authContent),
                "none", "noauth", "no_auth", "basic", "bearer", "apikey", "oauth2");
        if (nested == null) {
            return null;
        }
        return parseAuthBlock(nested.name, nested.content, legacyQuotedValues);
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
        for (String rawLine : BrunoSourceSupport.lines(content)) {
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
        String safePath = sanitizeImportLabel(relativePath != null && !relativePath.isBlank() ? relativePath : "unknown.bru");
        String sanitizedReason = sanitizeImportLabel(burp.diagnostics.DiagnosticSanitizer.sanitizeText(reason));
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
        return basicAuth(content, false);
    }

    private ApiRequest.Auth basicAuth(String content, boolean legacyQuotedValues) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "basic";
        auth.properties.put("username", firstNonBlank(extractValue(content, "username", legacyQuotedValues), extractValue(content, "user", legacyQuotedValues)));
        auth.properties.put("password", firstNonBlank(extractValue(content, "password", legacyQuotedValues), extractValue(content, "pass", legacyQuotedValues)));
        return auth;
    }

    private ApiRequest.Auth bearerAuth(String content) {
        return bearerAuth(content, false);
    }

    private ApiRequest.Auth bearerAuth(String content, boolean legacyQuotedValues) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "bearer";
        auth.properties.put("token", firstNonBlank(extractValue(content, "token", legacyQuotedValues), extractValue(content, "value", legacyQuotedValues), extractValue(content, "access_token", legacyQuotedValues)));
        return auth;
    }

    private ApiRequest.Auth apiKeyAuth(String content) {
        return apiKeyAuth(content, false);
    }

    private ApiRequest.Auth apiKeyAuth(String content, boolean legacyQuotedValues) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "apikey";
        auth.properties.put("key", firstNonBlank(extractValue(content, "key", legacyQuotedValues), extractValue(content, "name", legacyQuotedValues), extractValue(content, "keyname", legacyQuotedValues)));
        auth.properties.put("value", firstNonBlank(extractValue(content, "value", legacyQuotedValues), extractValue(content, "keyvalue", legacyQuotedValues), extractValue(content, "token", legacyQuotedValues)));
        String placement = firstNonBlank(extractValue(content, "in", legacyQuotedValues), extractValue(content, "placement", legacyQuotedValues), extractValue(content, "location", legacyQuotedValues));
        auth.properties.put("in", placement != null ? placement : "header");
        return auth;
    }

    private ApiRequest.Auth oauth2Auth(String content) {
        return oauth2Auth(content, false);
    }

    private ApiRequest.Auth oauth2Auth(String content, boolean legacyQuotedValues) {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "oauth2";
        String grantType = firstNonBlank(extractValue(content, "grant_type", legacyQuotedValues), extractValue(content, "grantType", legacyQuotedValues), extractValue(content, "grant", legacyQuotedValues));
        auth.properties.put("grantType", grantType != null ? grantType : "client_credentials");
        auth.properties.put("accessTokenUrl", firstNonBlank(extractValue(content, "access_token_url", legacyQuotedValues), extractValue(content, "accessTokenUrl", legacyQuotedValues)));
        auth.properties.put("authorizationUrl", firstNonBlank(extractValue(content, "authorization_url", legacyQuotedValues), extractValue(content, "authorizationUrl", legacyQuotedValues)));
        auth.properties.put("redirectUri", firstNonBlank(extractValue(content, "callback_url", legacyQuotedValues), extractValue(content, "redirect_uri", legacyQuotedValues), extractValue(content, "redirectUri", legacyQuotedValues)));
        auth.properties.put("clientId", firstNonBlank(extractValue(content, "client_id", legacyQuotedValues), extractValue(content, "clientId", legacyQuotedValues)));
        auth.properties.put("clientSecret", firstNonBlank(extractValue(content, "client_secret", legacyQuotedValues), extractValue(content, "clientSecret", legacyQuotedValues)));
        auth.properties.put("scope", extractValue(content, "scope", legacyQuotedValues));
        auth.properties.put("username", extractValue(content, "username", legacyQuotedValues));
        auth.properties.put("password", extractValue(content, "password", legacyQuotedValues));
        auth.properties.put("accessToken", firstNonBlank(extractValue(content, "access_token", legacyQuotedValues), extractValue(content, "accessToken", legacyQuotedValues), extractValue(content, "token", legacyQuotedValues)));
        return auth;
    }

    private DictionaryEntry parseDictionaryEntry(String rawLine) {
        return parseDictionaryEntry(rawLine, false);
    }

    private DictionaryEntry parseDictionaryEntry(String rawLine, boolean legacyQuotedValues) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty()) {
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
        boolean quotedKey = false;
        if (line.startsWith("\"")) {
            quotedKey = true;
            int i = findCanonicalQuotedKeyEnd(line);
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
            key = decodeBruQuotedKey(line.substring(1, i));
        } else {
            colonIndex = line.indexOf(':');
            if (colonIndex <= 0) {
                return null;
            }
            key = line.substring(0, colonIndex).trim();
        }

        if (key == null || (!quotedKey && key.isBlank())) {
            return null;
        }
        String authoredValue = line.substring(colonIndex + 1).trim();
        boolean legacyQuoted = authoredValue.length() >= 2
                && authoredValue.startsWith("\"") && authoredValue.endsWith("\"");
        String value = parseBrunoValue(authoredValue, legacyQuotedValues);
        return new DictionaryEntry(key, value, disabled, "string", null, legacyQuoted);
    }

    private int findCanonicalQuotedKeyEnd(String line) {
        for (int index = 1; line != null && index < line.length(); index++) {
            if (line.charAt(index) != '"') continue;
            int slashCount = 0;
            for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) slashCount++;
            if ((slashCount & 1) == 1) continue;
            int cursor = index + 1;
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
            if (cursor < line.length() && line.charAt(cursor) == ':') return index;
        }
        return line != null ? line.length() : 0;
    }

    private String parseBrunoValue(String value, boolean legacyQuotedValues) {
        if (!legacyQuotedValues || value == null || value.length() < 2
                || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        return decodeLegacyBruQuotedValue(value.substring(1, value.length() - 1));
    }

    private String decodeBruQuotedKey(String encoded) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; encoded != null && index < encoded.length(); index++) {
            char ch = encoded.charAt(index);
            if (ch == '\\' && index + 1 < encoded.length() && encoded.charAt(index + 1) == '"') {
                result.append('"');
                index++;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private String decodeLegacyBruQuotedValue(String encoded) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; encoded != null && i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            if (ch != '\\' || i + 1 >= encoded.length()) {
                result.append(ch);
                continue;
            }
            char escaped = encoded.charAt(++i);
            switch (escaped) {
                case '\\' -> result.append('\\');
                case '"' -> result.append('"');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'u' -> {
                    if (i + 4 < encoded.length()) {
                        String digits = encoded.substring(i + 1, i + 5);
                        try {
                            result.append((char) Integer.parseInt(digits, 16));
                            i += 4;
                        } catch (NumberFormatException invalidUnicode) {
                            result.append("\\u");
                        }
                    } else {
                        result.append("\\u");
                    }
                }
                default -> result.append('\\').append(escaped);
            }
        }
        return result.toString();
    }

    private List<ApiRequest.Header> parseHeadersBlock(BrunoBlockScanner.Block headersBlock,
                                                      boolean legacyQuotedValues) {
        List<ApiRequest.Header> headers = new ArrayList<>();
        if (headersBlock == null || headersBlock.content == null) {
            return headers;
        }
        for (DictionaryEntry entry : parseDictionaryEntries(headersBlock.content, legacyQuotedValues)) {
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
                                 String displayPath,
                                 boolean legacyQuotedValues) throws IOException {
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
                    req.body.graphql.query = graphqlBlock != null ? BrunoSourceSupport.decodeTextBlock(graphqlBlock.content, true) : "";
                    req.body.graphql.variables = graphqlVarsBlock != null && !graphqlVarsBlock.content.isBlank()
                            ? BrunoSourceSupport.decodeTextBlock(graphqlVarsBlock.content, true)
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
                    req.body.urlencoded = urlencodedBlock != null ? parseFormFields(urlencodedBlock.content, false, collection, displayPath, legacyQuotedValues) : new ArrayList<>();
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
                    req.body.formdata = multipartBlock != null ? parseFormFields(multipartBlock.content, true, collection, displayPath, legacyQuotedValues) : new ArrayList<>();
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
                case "file" -> {
                    parseFileBody(req, BrunoBlockScanner.firstByName(blocks, "body:file"), collection,
                            displayPath, legacyQuotedValues);
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
                    req.body.raw = selectedBlock != null ? BrunoSourceSupport.decodeTextBlock(selectedBlock.content, true) : "";
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
            req.body.graphql.query = graphqlBlock != null ? BrunoSourceSupport.decodeTextBlock(graphqlBlock.content) : "";
            req.body.graphql.variables = graphqlVarsBlock != null && !graphqlVarsBlock.content.isBlank()
                    ? BrunoSourceSupport.decodeTextBlock(graphqlVarsBlock.content)
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
            req.body.formdata = parseFormFields(multipartBlock.content, true, collection, displayPath, legacyQuotedValues);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            return;
        }

        BrunoBlockScanner.Block urlencodedBlock = BrunoBlockScanner.firstByName(blocks, "body:form-urlencoded");
        if (urlencodedBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "urlencoded";
            req.body.urlencoded = parseFormFields(urlencodedBlock.content, false, collection, displayPath, legacyQuotedValues);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/x-www-form-urlencoded";
            }
            return;
        }

        BrunoBlockScanner.Block fileBlock = BrunoBlockScanner.firstByName(blocks, "body:file");
        if (fileBlock != null) {
            parseFileBody(req, fileBlock, collection, displayPath, legacyQuotedValues);
            return;
        }

        BrunoBlockScanner.Block jsonBlock = BrunoBlockScanner.firstByName(blocks, "body:json");
        if (jsonBlock != null) {
            req.body = new ApiRequest.Body();
            req.body.mode = "raw";
            req.body.raw = BrunoSourceSupport.decodeTextBlock(jsonBlock.content);
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
            req.body.raw = BrunoSourceSupport.decodeTextBlock(textBlock.content);
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
            req.body.raw = BrunoSourceSupport.decodeTextBlock(xmlBlock.content);
            req.body.contentType = firstEnabledHeaderValue(req.headers, "Content-Type");
            if (req.body.contentType == null || req.body.contentType.isBlank()) {
                req.body.contentType = "application/xml";
            }
            return;
        }

        BrunoBlockScanner.Block legacyBodyBlock = BrunoBlockScanner.firstByName(blocks, "body");
        if (legacyBodyBlock != null) {
            String raw = BrunoSourceSupport.decodeTextBlock(legacyBodyBlock.content);
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

    private List<ApiRequest.Body.FormField> parseFormFields(String content, boolean multipart,
                                                             ApiCollection collection, String displayPath,
                                                             boolean legacyQuotedValues) throws IOException {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return fields;
        }

        if (content.contains("'''")) {
            for (DictionaryEntry entry : parseDictionaryEntries(content, legacyQuotedValues)) {
                fields.add(createFormField(entry.key, entry.value, entry.disabled,
                        isFileFieldValue(entry.value), multipart, false));
                warnMultipartContentType(entry.value, multipart, collection, displayPath, entry.key);
            }
            return fields;
        }

        StringBuilder currentBuffer = new StringBuilder();
        String currentKey = null;
        boolean currentDisabled = false;
        boolean currentFileUpload = false;
        boolean currentHasContinuation = false;
        BraceState currentState = new BraceState(0, false, false, false);

        for (String rawLine : BrunoSourceSupport.lines(content)) {
            DictionaryEntry entry = parseDictionaryEntry(rawLine, legacyQuotedValues);
            boolean startOfField = entry != null && (currentKey == null || currentState.depth == 0);

            if (startOfField) {
                if (currentKey != null) {
                    fields.add(createFormField(currentKey, currentBuffer.toString(), currentDisabled,
                            currentFileUpload, multipart, currentHasContinuation));
                }
                currentKey = null;
                currentBuffer.setLength(0);
                currentState = new BraceState(0, false, false, false);
                currentDisabled = entry.disabled;
                currentHasContinuation = false;
                currentKey = entry.key;
                String initialValue = entry.value != null ? entry.value : "";
                currentBuffer.append(initialValue);
                currentState = updateBraceState(initialValue, currentState.inSingleQuote, currentState.inDoubleQuote, currentState.escaped, currentState.depth);
                currentFileUpload = isFileFieldValue(currentBuffer.toString());
                warnMultipartContentType(initialValue, multipart, collection, displayPath, entry.key);
                continue;
            }

            if (currentKey == null) {
                continue;
            }

            if (currentState.depth == 0 && rawLine.isBlank()) {
                continue;
            }

            if (currentBuffer.length() > 0) {
                currentBuffer.append('\n');
            }
            currentBuffer.append(rawLine);
            currentHasContinuation = true;
            currentState = updateBraceState(rawLine, currentState.inSingleQuote, currentState.inDoubleQuote, currentState.escaped, currentState.depth);
            currentFileUpload = currentFileUpload || isFileFieldValue(currentBuffer.toString());
        }

        if (currentKey != null) {
            fields.add(createFormField(currentKey, currentBuffer.toString(), currentDisabled,
                    currentFileUpload, multipart, currentHasContinuation));
        }

        return fields;
    }

    private ApiRequest.Body.FormField createFormField(String key, String value, boolean disabled,
                                                      boolean fileUpload, boolean multipart,
                                                      boolean hasPhysicalContinuation) {
        String importedValue = value != null ? value : "";
        if (hasPhysicalContinuation) importedValue = trimBlockContent(importedValue);
        ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, importedValue);
        field.disabled = disabled;
        if (multipart && fileUpload) {
            field.fileUpload = true;
            field.type = "file";
            field.filePath = extractFilePath(field.value);
            if (field.filePath != null && field.filePath.isBlank()) {
                field.filePath = null;
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
        return trimmed.startsWith("@file(") && findFileDeclarationEnd(trimmed) >= "@file(".length();
    }

    private String extractFilePath(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        int end = findFileDeclarationEnd(trimmed);
        if (!trimmed.startsWith("@file(") || end < "@file(".length()) {
            return null;
        }
        return trimmed.substring("@file(".length(), end);
    }

    private int findFileDeclarationEnd(String value) {
        if (value == null || !value.startsWith("@file(")) return -1;
        String marker = ") @contentType(";
        int annotated = value.indexOf(marker, "@file(".length());
        if (annotated >= 0 && value.endsWith(")")) return annotated;
        return value.endsWith(")") ? value.length() - 1 : -1;
    }

    private void warnMultipartContentType(String value, boolean multipart, ApiCollection collection,
                                          String displayPath, String key) {
        if (multipart && value != null && value.contains(") @contentType(")) {
            recordImportWarning(collection, displayPath,
                    "Multipart field '" + sanitizeImportLabel(key)
                            + "' retained without its per-field content type metadata.", false);
        }
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

    private void parseRequestVariables(ApiRequest req, List<BrunoBlockScanner.Block> blocks,
                                       ApiCollection collection, String displayPath,
                                       boolean legacyQuotedValues) {
        if (req == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        for (DictionaryEntry entry : parseVarsEntries(blocks, legacyQuotedValues)) {
            if (entry == null || entry.key == null) {
                continue;
            }
            ApiRequest.Variable variable = new ApiRequest.Variable();
            variable.key = entry.key;
            variable.value = entry.value;
            variable.enabled = !entry.disabled;
            variable.type = entry.type;
            req.variables.add(variable);
            if (!isValidTypedVariable(entry.type, entry.value)) {
                recordImportWarning(collection, displayPath,
                        "Variable '" + entry.key + "' retained invalid " + entry.type + " content as text.", false);
            }
        }
    }

    private boolean isValidTypedVariable(String type, String value) {
        if (type == null || "string".equalsIgnoreCase(type)) return true;
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "number" -> { new java.math.BigDecimal(value); yield true; }
                case "boolean" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                case "object" -> com.google.gson.JsonParser.parseString(value).isJsonObject();
                default -> true;
            };
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void parseScripts(ApiRequest req, File file, List<BrunoBlockScanner.Block> blocks) {
        if (req == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        int order = 0;
        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null || block.content == null) {
                continue;
            }
            String name = block.normalizedName();
            ScriptPhase phase = switch (name) {
                case "script:pre-request" -> ScriptPhase.PRE_REQUEST;
                case "script:post-response" -> ScriptPhase.POST_RESPONSE;
                case "assert", "test", "tests", "body:test" -> ScriptPhase.TEST;
                default -> null;
            };
            if (phase == null) {
                continue;
            }
            ApiRequest.Script script = new ApiRequest.Script("js",
                    BrunoSourceSupport.decodeStructuralTextBlock(block.content));
            if (phase == ScriptPhase.PRE_REQUEST) {
                req.preRequestScripts.add(script);
            } else {
                req.postResponseScripts.add(script);
            }
            addScriptBlock(req, script, ScriptDialect.BRUNO, phase, file, order++);
        }
    }

    private void importScopedScripts(ApiCollection collection, String folderPath,
                                     List<BrunoBlockScanner.Block> blocks, String sourcePath) {
        if (collection == null || blocks == null) return;
        String normalizedFolder = AuthInheritanceResolver.normalizeFolderPath(folderPath);
        List<ScriptBlock> target = normalizedFolder.isEmpty()
                ? collection.scriptBlocks
                : collection.folderScriptBlocks.computeIfAbsent(normalizedFolder, ignored -> new ArrayList<>());
        int order = target.size();
        for (BrunoBlockScanner.Block block : blocks) {
            if (block == null || block.name == null) continue;
            ScriptPhase phase = switch (block.normalizedName()) {
                case "script:pre-request" -> ScriptPhase.PRE_REQUEST;
                case "script:post-response" -> ScriptPhase.POST_RESPONSE;
                case "tests", "test", "assert", "body:test" -> ScriptPhase.TEST;
                default -> null;
            };
            if (phase == null) continue;
            ScriptBlock script = ScriptBlock.of(
                    BrunoSourceSupport.decodeStructuralTextBlock(block.content),
                    ScriptDialect.BRUNO, phase,
                    normalizedFolder.isEmpty() ? ScriptScope.COLLECTION : ScriptScope.FOLDER);
            script.sourceFormat = "bruno";
            script.sourcePath = sourcePath;
            script.order = order++;
            target.add(script);
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
        String reason = exception != null
                ? exception.getClass().getSimpleName()
                : "malformed Bruno request";
        String sanitizedReason = sanitizeImportLabel(burp.diagnostics.DiagnosticSanitizer.sanitizeText(reason));
        relativePath = sanitizeImportLabel(relativePath);
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
            if (normalized.equals(methodMatch.blockName.toLowerCase(Locale.ROOT))) {
                return block.content;
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
                return value;
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
        return extractValue(block, key, false);
    }

    private String extractValue(String block, String key, boolean legacyQuotedValues) {
        if (block == null || key == null || key.isBlank()) {
            return null;
        }
        for (DictionaryEntry entry : parseDictionaryEntries(block, legacyQuotedValues)) {
            if (entry != null && key.equalsIgnoreCase(entry.key)) {
                return entry.value == null || entry.value.isEmpty() ? null : entry.value;
            }
        }
        return null;
    }

    private String sanitizeImportLabel(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\u0085\\u2028\\u2029\\x00-\\x09\\x0B\\x0C\\x0E-\\x1F\\x7F]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean containsLegacyQuotedDictionaryValue(String content) {
        for (String line : BrunoSourceSupport.lines(content)) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                    && value.substring(1, value.length() - 1).contains("\\")) {
                return true;
            }
        }
        return false;
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
        private final String blockName;

        private BrunoMethodMatch(String method, String sameLineUrl, String blockName) {
            this.method = method;
            this.sameLineUrl = sameLineUrl;
            this.blockName = blockName;
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
        private final String type;
        private final String description;
        private final boolean legacyQuoted;

        private DictionaryEntry(String key, String value, boolean disabled) {
            this(key, value, disabled, "string", null, false);
        }

        private DictionaryEntry(String key, String value, boolean disabled,
                                String type, String description, boolean legacyQuoted) {
            this.key = key;
            this.value = value;
            this.disabled = disabled;
            this.type = type != null ? type : "string";
            this.description = description;
            this.legacyQuoted = legacyQuoted;
        }
    }

    private record DescriptionParse(String value, int lastLine) { }

    private void parseFileBody(ApiRequest req,
                               BrunoBlockScanner.Block fileBlock,
                               ApiCollection collection,
                               String displayPath,
                               boolean legacyQuotedValues) {
        req.body = new ApiRequest.Body();
        req.body.mode = "file";
        req.body.raw = "";
        if (fileBlock == null) {
            recordImportWarning(collection, displayPath,
                    "Selected Bruno body mode 'file' was not found; imported an empty file body.", false);
            return;
        }
        String declaration = null;
        for (String line : BrunoSourceSupport.lines(fileBlock.content)) {
            DictionaryEntry entry = parseDictionaryEntry(line, legacyQuotedValues);
            if (entry != null && "file".equalsIgnoreCase(entry.key)) {
                declaration = entry.value;
                break;
            }
        }
        if (declaration == null) {
            recordImportWarning(collection, displayPath,
                    "Bruno file body did not contain a file declaration; imported an empty file body.", false);
            return;
        }
        int fileStart = declaration.indexOf("@file(");
        int contentTypeStart = declaration.lastIndexOf(" @contentType(");
        int fileEnd = contentTypeStart >= 0 ? contentTypeStart - 1 : declaration.lastIndexOf(')');
        if (fileStart == 0 && fileEnd >= "@file(".length()) {
            req.body.raw = declaration.substring("@file(".length(), fileEnd);
        }
        if (contentTypeStart >= 0 && declaration.endsWith(")")) {
            req.body.contentType = declaration.substring(
                    contentTypeStart + " @contentType(".length(), declaration.length() - 1);
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
