package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.ui.tree.RequestTreePathService;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestParameterSupport;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BrunoCollectionExporter {
    private static final Set<String> STANDARD_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT");
    private static final Set<String> SUPPORTED_AUTH = Set.of("none", "basic", "bearer", "apikey", "oauth2");
    private static final Set<String> RESERVED_NAMES = Set.of(
            "bruno.json", "collection.bru", "folder.bru", "_collection.bru", "_folder.bru");

    private BrunoCollectionExporter() {
    }

    public static void write(ApiCollection collection,
                             CollectionExportOptions options,
                             OutputStream outputStream,
                             List<String> warnings) throws IOException {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();
        String collectionName = collection != null ? collection.name : null;
        String rootSegment = archiveSegment(collectionName, "collection");
        if (collectionName != null && !collectionName.equals(rootSegment)) {
            ExportWarningSupport.add(warnings, "Bruno export normalized an unsafe collection archive label '"
                    + ExportWarningSupport.label(collectionName) + "'.");
        }
        String rootDir = rootSegment + "/";
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();

        if (collection != null) {
            entries.put(rootDir + "bruno.json", gsonBytes(brunoJson(collection)));
            VariableResolver collectionResolver = CollectionExportSupport.buildResolver(
                    collection, null, resolve ? activeEnvironment : null, exportOnly);
            entries.put(rootDir + "collection.bru", textBytes(renderCollectionMetadata(
                    collection, collectionResolver, resolve, warnings)));
            appendCollectionEnvironment(entries, rootDir, collection, collectionResolver, resolve, warnings);
            CollectionExportTree.FolderNode tree = CollectionExportTree.build(collection);
            Map<ApiRequest, Integer> requestOrder = new java.util.IdentityHashMap<>();
            int sequence = 1;
            for (ApiRequest request : collection.requests != null ? collection.requests : List.<ApiRequest>of()) {
                if (request != null) requestOrder.put(request, sequence++);
            }
            writeNode(entries, collection, tree, rootDir, activeEnvironment,
                    exportOnly, resolve, warnings, requestOrder);
        }

        validateEntries(entries.keySet());
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static Map<String, Object> brunoJson(ApiCollection collection) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "1");
        json.put("name", collection.name != null ? collection.name : "Collection");
        json.put("type", "collection");
        return json;
    }

    private static String renderCollectionMetadata(ApiCollection collection,
                                                   VariableResolver resolver,
                                                   boolean resolve,
                                                   List<String> warnings) throws IOException {
        StringBuilder out = new StringBuilder();
        if (resolve) ExportVariableResolutionService.addDuplicateEnabledVariableWarnings(collection, null, warnings);
        appendVariables(out, collection.variables, resolver, resolve,
                "collection '" + ExportWarningSupport.label(collection.name) + "'", warnings);
        appendAuthBlock(out, collection.auth, "collection '" + ExportWarningSupport.label(collection.name) + "'",
                resolver, resolve, warnings);
        return out.toString();
    }

    private static void appendCollectionEnvironment(Map<String, byte[]> entries,
                                                    String rootDir,
                                                    ApiCollection collection,
                                                    VariableResolver resolver,
                                                    boolean resolve,
                                                    List<String> warnings) throws IOException {
        if (collection.environment == null || collection.environment.isEmpty()) return;
        StringBuilder out = new StringBuilder("vars {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        for (Map.Entry<String, String> entry : collection.environment.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = BrunoFormatSupport.uniqueRenderedKey(entry.getKey(), keys,
                    "collection environment", warnings);
            String value = CollectionExportSupport.resolve(entry.getValue(), resolver, resolve);
            BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key,
                    value != null ? value : "", "collection environment", warnings);
        }
        out.append("}\n");
        entries.put(rootDir + "environments/Environment.bru", textBytes(out.toString()));
    }

    private static void writeNode(Map<String, byte[]> entries,
                                  ApiCollection collection,
                                  CollectionExportTree.FolderNode node,
                                  String archiveDir,
                                  EnvironmentProfile activeEnvironment,
                                  Map<String, String> exportOnly,
                                  boolean resolve,
                                  List<String> warnings,
                                  Map<ApiRequest, Integer> requestOrder) throws IOException {
        Set<String> used = new LinkedHashSet<>();
        for (String reserved : RESERVED_NAMES) used.add(reserved.toLowerCase(Locale.ROOT));
        Map<CollectionExportTree.FolderNode, String> childDirs = new LinkedHashMap<>();
        for (CollectionExportTree.FolderNode child : node.children.values()) {
            String segment = allocateName(child.name, "folder", null, used, warnings);
            String childDir = archiveDir + segment + "/";
            childDirs.put(child, childDir);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection,
                    dummyRequestForFolder(child.path), resolve ? activeEnvironment : null, exportOnly);
            entries.put(childDir + "folder.bru", textBytes(renderFolderMetadata(
                    collection, child, resolver, resolve, warnings)));
        }
        for (ApiRequest request : node.requests) {
            if (request == null) continue;
            String fileName = allocateName(request.name, "request", ".bru", used, warnings);
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request,
                    resolve ? activeEnvironment : null, exportOnly);
            if (resolve) ExportVariableResolutionService.addDuplicateEnabledVariableWarnings(collection, request, warnings);
            entries.put(archiveDir + fileName, textBytes(renderRequest(
                    request, resolver, resolve, requestOrder.getOrDefault(request, 1), warnings)));
        }
        for (Map.Entry<CollectionExportTree.FolderNode, String> child : childDirs.entrySet()) {
            writeNode(entries, collection, child.getKey(), child.getValue(), activeEnvironment,
                    exportOnly, resolve, warnings, requestOrder);
        }
    }

    private static String renderFolderMetadata(ApiCollection collection,
                                               CollectionExportTree.FolderNode node,
                                               VariableResolver resolver,
                                               boolean resolve,
                                               List<String> warnings) throws IOException {
        StringBuilder out = new StringBuilder();
        String folderPath = RequestTreePathService.normalizeFolderPath(node.path);
        Map<String, String> vars = collection.folderVars != null ? collection.folderVars.get(folderPath) : null;
        if (vars != null && !vars.isEmpty()) {
            out.append("vars:pre-request {\n");
            Set<String> keys = BrunoFormatSupport.newKeySet();
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = BrunoFormatSupport.uniqueRenderedKey(entry.getKey(), keys,
                        "folder '" + ExportWarningSupport.label(folderPath) + "' variables", warnings);
                String value = CollectionExportSupport.resolve(entry.getValue(), resolver, resolve);
                BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key, value != null ? value : "",
                        "folder '" + ExportWarningSupport.label(folderPath) + "' variable", warnings);
            }
            out.append("}\n\n");
        }
        String mode = collection.folderAuthModes != null ? collection.folderAuthModes.get(folderPath) : null;
        if (mode != null && !"inherit".equalsIgnoreCase(mode)) {
            ApiRequest.Auth auth = collection.folderAuth != null ? collection.folderAuth.get(folderPath) : null;
            if ("none".equalsIgnoreCase(mode)) auth = noneAuth();
            appendAuthBlock(out, auth, "folder '" + ExportWarningSupport.label(folderPath) + "'",
                    resolver, resolve, warnings);
        }
        return out.toString();
    }

    private static String renderRequest(ApiRequest request,
                                        VariableResolver resolver,
                                        boolean resolve,
                                        int sequence,
                                        List<String> warnings) throws IOException {
        String requestLabel = "request '" + ExportWarningSupport.label(request.name) + "'";
        String method = request.method != null && !request.method.isEmpty() ? request.method : "GET";
        validateMethod(method, requestLabel);
        String bodySelector = bodySelector(request.body);
        AuthSelection authSelection = requestAuthSelection(request, warnings);
        StringBuilder out = new StringBuilder();
        out.append("meta {\n");
        appendSimpleEntry(out, "name", request.name != null ? request.name : "Request", requestLabel, warnings);
        out.append("  type: http\n");
        out.append("  seq: ").append(sequence).append("\n");
        out.append("}\n\n");

        String methodBlock = STANDARD_METHODS.contains(method.toUpperCase(Locale.ROOT))
                ? method.toLowerCase(Locale.ROOT) : "http";
        out.append(methodBlock).append(" {\n");
        if ("http".equals(methodBlock)) appendSimpleEntry(out, "method", method, requestLabel + " method", warnings);
        String exportedUrl = RequestParameterSupport.materializeUrl(
                request.url, brunoTransportParameters(request, resolver, resolve, warnings),
                resolve ? resolver : null);
        appendSimpleEntry(out, "url", exportedUrl != null ? exportedUrl : "", requestLabel + " URL", warnings);
        out.append("  body: ").append(bodySelector).append("\n");
        if (authSelection.selector != null) out.append("  auth: ").append(authSelection.selector).append("\n");
        out.append("}\n\n");

        appendParameterBlocks(out, request, resolver, resolve, warnings);
        appendHeaders(out, request, resolver, resolve, warnings);
        appendTypedBody(out, request, resolver, resolve, warnings);
        if (authSelection.auth != null && !"none".equals(authSelection.selector)) {
            appendAuthBlock(out, authSelection.auth, requestLabel, resolver, resolve, warnings);
        }
        appendScripts(out, request, resolver, resolve, warnings);
        appendVariables(out, request.variables, resolver, resolve, requestLabel, warnings);
        return out.toString();
    }

    private static void appendSimpleEntry(StringBuilder out, String key, String value,
                                          String context, List<String> warnings) throws IOException {
        BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key,
                BrunoFormatSupport.sanitizeText(value, context, warnings), context, warnings);
    }

    private static void appendParameterBlocks(StringBuilder out,
                                              ApiRequest request,
                                              VariableResolver resolver,
                                              boolean resolve,
                                              List<String> warnings) throws IOException {
        List<ApiRequest.Parameter> query = new ArrayList<>();
        List<ApiRequest.Parameter> path = new ArrayList<>();
        Set<String> unsupported = new LinkedHashSet<>();
        if (request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter == null) continue;
                if (parameter.isQuery()) query.add(parameter);
                else if ("path".equalsIgnoreCase(parameter.location)) path.add(parameter);
                if (parameter.required) unsupported.add("required");
                if (parameter.style != null && !parameter.style.isBlank()) unsupported.add("style");
                if (parameter.explode != null) unsupported.add("explode");
                if (parameter.allowReserved) unsupported.add("allowReserved");
                if (parameter.source != null && !parameter.source.isBlank()) unsupported.add("source");
                if (parameter.type != null && !parameter.type.isBlank()) unsupported.add("type");
            }
        }
        appendParameterBlock(out, "params:query", query, request, resolver, resolve, warnings, true);
        appendParameterBlock(out, "params:path", path, request, resolver, resolve, warnings, false);
        if (!unsupported.isEmpty()) {
            ExportWarningSupport.add(warnings, "Bruno export for request '" + ExportWarningSupport.label(request.name)
                    + "' omitted unsupported parameter metadata: " + String.join(", ", unsupported) + ".");
        }
    }

    private static List<ApiRequest.Parameter> brunoTransportParameters(ApiRequest request,
                                                                       VariableResolver resolver,
                                                                       boolean resolve,
                                                                       List<String> warnings) {
        List<ApiRequest.Parameter> copy = burp.utils.RequestParameterSupport.copyParameters(request.parameters);
        Set<String> usedKeys = BrunoFormatSupport.newKeySet();
        for (ApiRequest.Parameter parameter : copy) {
            if (parameter == null || !parameter.isQuery()) continue;
            String key = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
            String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
            parameter.key = BrunoFormatSupport.uniqueKeyText(key, usedKeys, "query parameter", warnings);
            parameter.value = BrunoFormatSupport.normalizePhysicalLines(
                    BrunoFormatSupport.sanitizeText(value, "query parameter value", warnings));
            parameter.rawKey = null;
            parameter.rawValue = null;
        }
        return copy;
    }

    private static void appendParameterBlock(StringBuilder out,
                                             String blockName,
                                             List<ApiRequest.Parameter> parameters,
                                             ApiRequest request,
                                             VariableResolver resolver,
                                             boolean resolve,
                                             List<String> warnings,
                                             boolean query) throws IOException {
        List<ApiRequest.Parameter> emitted = new ArrayList<>();
        for (ApiRequest.Parameter parameter : parameters) {
            if (!query || parameter.valuePresent || parameter.disabled) {
                emitted.add(parameter);
            } else if (parameter.description != null && !parameter.description.isBlank()) {
                ExportWarningSupport.add(warnings, "Bruno export could not attach the description of enabled bare query parameter '"
                        + ExportWarningSupport.label(parameter.key) + "' in request '"
                        + ExportWarningSupport.label(request.name) + "'.");
            }
        }
        if (emitted.isEmpty()) return;
        out.append(blockName).append(" {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        for (ApiRequest.Parameter parameter : emitted) {
            if (!parameter.valuePresent) {
                String kind = query ? "disabled bare query" : "bare path";
                ExportWarningSupport.add(warnings, "Bruno export for request '"
                        + ExportWarningSupport.label(request.name) + "' cannot preserve " + kind
                        + " parameter '" + ExportWarningSupport.label(parameter.key)
                        + "'; exported it as " + (query ? "disabled " : "") + "explicit-empty.");
            }
            if (parameter.description != null && !parameter.description.isBlank()) {
                String description = BrunoFormatSupport.sanitizeText(
                        CollectionExportSupport.resolve(parameter.description, resolver, resolve),
                        "parameter description", warnings);
                if (description.indexOf('\n') < 0 && description.indexOf('\r') < 0
                        && description.indexOf(')') < 0) {
                    out.append("  @description(").append(description).append(")\n");
                } else {
                    ExportWarningSupport.add(warnings, "Bruno export omitted a multiline parameter description for request '"
                            + ExportWarningSupport.label(request.name) + "'.");
                }
            }
            String keyValue = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
            String key = BrunoFormatSupport.uniqueRenderedKey(keyValue, keys,
                    "parameter block in request '" + ExportWarningSupport.label(request.name) + "'", warnings);
            String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
            BrunoFormatSupport.appendDictionaryEntry(out, "  ", parameter.disabled ? "~" : "", key,
                    value != null ? value : "", "parameter in request '"
                            + ExportWarningSupport.label(request.name) + "'", warnings);
        }
        out.append("}\n\n");
    }

    private static void appendHeaders(StringBuilder out, ApiRequest request,
                                      VariableResolver resolver, boolean resolve,
                                      List<String> warnings) throws IOException {
        boolean synthesizeContentType = request.body != null
                && request.body.contentType != null && !request.body.contentType.isBlank()
                && !hasEnabledHeader(request.headers, "Content-Type");
        if ((request.headers == null || request.headers.isEmpty()) && !synthesizeContentType) return;
        out.append("headers {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        for (ApiRequest.Header header : request.headers != null ? request.headers : List.<ApiRequest.Header>of()) {
            if (header == null || header.key == null || header.key.isBlank()
                    || CollectionExportSupport.isTransportHeader(header.key)) continue;
            String renderedKey = BrunoFormatSupport.uniqueRenderedKey(
                    CollectionExportSupport.resolve(header.key, resolver, resolve), keys,
                    "headers in request '" + ExportWarningSupport.label(request.name) + "'", warnings);
            String value = CollectionExportSupport.resolve(header.value, resolver, resolve);
            BrunoFormatSupport.appendDictionaryEntry(out, "  ", header.disabled ? "~" : "", renderedKey,
                    value != null ? value : "", "header in request '"
                            + ExportWarningSupport.label(request.name) + "'", warnings);
        }
        if (synthesizeContentType) {
            String key = BrunoFormatSupport.uniqueRenderedKey("Content-Type", keys, "headers", warnings);
            BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key, request.body.contentType,
                    "content type", warnings);
        }
        out.append("}\n\n");
    }

    private static void appendTypedBody(StringBuilder out, ApiRequest request,
                                        VariableResolver resolver, boolean resolve,
                                        List<String> warnings) throws IOException {
        ApiRequest.Body body = request.body;
        String selector = bodySelector(body);
        if ("none".equals(selector)) return;
        switch (selector) {
            case "graphql" -> {
                BrunoFormatSupport.appendTextBlock(out, "body:graphql", CollectionExportSupport.resolve(
                        body.graphql != null ? body.graphql.query : "", resolver, resolve),
                        "GraphQL query for request '" + ExportWarningSupport.label(request.name) + "'", warnings);
                BrunoFormatSupport.appendTextBlock(out, "body:graphql:vars", CollectionExportSupport.resolve(
                        body.graphql != null ? body.graphql.variables : "{}", resolver, resolve),
                        "GraphQL variables for request '" + ExportWarningSupport.label(request.name) + "'", warnings);
            }
            case "form-urlencoded" -> appendFieldBody(out, "body:form-urlencoded", body.urlencoded,
                    false, request, resolver, resolve, warnings);
            case "multipart-form" -> appendFieldBody(out, "body:multipart-form", body.formdata,
                    true, request, resolver, resolve, warnings);
            case "file" -> appendFileBody(out, request, resolver, resolve, warnings);
            case "json", "text", "xml" -> BrunoFormatSupport.appendTextBlock(out, "body:" + selector,
                    CollectionExportSupport.resolve(body.raw, resolver, resolve),
                    "body for request '" + ExportWarningSupport.label(request.name) + "'", warnings);
            default -> { }
        }
    }

    private static void appendFileBody(StringBuilder out, ApiRequest request,
                                       VariableResolver resolver, boolean resolve,
                                       List<String> warnings) throws IOException {
        String path = CollectionExportSupport.resolve(request.body.raw, resolver, resolve);
        if (!BrunoFormatSupport.isSafeFilePath(path != null ? path : "")) {
            throw new IOException("Bruno export cannot represent the file body path for request '"
                    + ExportWarningSupport.label(request.name) + "'.");
        }
        out.append("body:file {\n  file: @file(").append(path != null ? path : "").append(')');
        if (request.body.contentType != null && !request.body.contentType.isBlank()) {
            String contentType = BrunoFormatSupport.sanitizeText(request.body.contentType,
                    "file body content type", warnings);
            if (contentType.indexOf(')') >= 0 || contentType.indexOf('\n') >= 0) {
                throw new IOException("Bruno export cannot represent the file body content type for request '"
                        + ExportWarningSupport.label(request.name) + "'.");
            }
            out.append(" @contentType(").append(contentType).append(')');
        }
        out.append("\n}\n\n");
    }

    private static void appendFieldBody(StringBuilder out,
                                        String block,
                                        List<ApiRequest.Body.FormField> fields,
                                        boolean multipart,
                                        ApiRequest request,
                                        VariableResolver resolver,
                                        boolean resolve,
                                        List<String> warnings) throws IOException {
        out.append(block).append(" {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        if (fields != null) {
            for (ApiRequest.Body.FormField field : fields) {
                if (field == null || field.key == null) continue;
                String renderedKey = BrunoFormatSupport.uniqueRenderedKey(
                        CollectionExportSupport.resolve(field.key, resolver, resolve), keys,
                        "body fields in request '" + ExportWarningSupport.label(request.name) + "'", warnings);
                boolean file = multipart && (field.fileUpload || "file".equalsIgnoreCase(field.type));
                String prefix = field.disabled ? "~" : "";
                if (file) {
                    String path = CollectionExportSupport.resolve(field.filePath, resolver, resolve);
                    if (path != null && BrunoFormatSupport.isSafeFilePath(path)) {
                        out.append("  ").append(prefix).append(renderedKey).append(": @file(")
                                .append(path).append(")\n");
                        String canonicalValue = "@file(" + path + ")";
                        if (field.value != null && !field.value.isEmpty()
                                && !canonicalValue.equals(field.value)) {
                            ExportWarningSupport.add(warnings, "Bruno export omitted the retained text value for multipart file field '"
                                    + ExportWarningSupport.label(field.key) + "' in request '"
                                    + ExportWarningSupport.label(request.name) + "'.");
                        }
                    } else if (field.value != null && BrunoFormatSupport.isSafeFilePath(field.value)) {
                        ExportWarningSupport.add(warnings, "Bruno export retained file field '"
                                + ExportWarningSupport.label(field.key) + "' for request '"
                                + ExportWarningSupport.label(request.name)
                                + "' as text because its file path is not safely representable.");
                        BrunoFormatSupport.appendDictionaryEntry(out, "  ", prefix, renderedKey,
                                CollectionExportSupport.resolve(field.value, resolver, resolve),
                                "multipart fallback field", warnings);
                    } else {
                        throw new IOException("Bruno export cannot safely represent a multipart file field for request '"
                                + ExportWarningSupport.label(request.name) + "'.");
                    }
                } else {
                    String value = CollectionExportSupport.resolve(field.value, resolver, resolve);
                    BrunoFormatSupport.appendDictionaryEntry(out, "  ", prefix, renderedKey,
                            value != null ? value : "", "body field in request '"
                                    + ExportWarningSupport.label(request.name) + "'", warnings);
                }
            }
        }
        out.append("}\n\n");
    }

    private static void appendVariables(StringBuilder out,
                                        List<ApiRequest.Variable> variables,
                                        VariableResolver resolver,
                                        boolean resolve,
                                        String context,
                                        List<String> warnings) throws IOException {
        if (variables == null || variables.isEmpty()) return;
        out.append("vars:pre-request {\n");
        Set<String> keys = BrunoFormatSupport.newKeySet();
        for (ApiRequest.Variable variable : variables) {
            if (variable == null || variable.key == null) continue;
            String type = variable.type == null || variable.type.isBlank()
                    ? "string" : variable.type.toLowerCase(Locale.ROOT);
            if (!Set.of("string", "number", "boolean", "object").contains(type)) {
                ExportWarningSupport.add(warnings, "Bruno export represented unsupported variable type '"
                        + ExportWarningSupport.label(type) + "' as string in " + context + ".");
                type = "string";
            }
            if (!"string".equals(type)) out.append("  @").append(type).append('\n');
            String key = BrunoFormatSupport.uniqueRenderedKey(
                    CollectionExportSupport.resolve(variable.key, resolver, resolve), keys,
                    context + " variables", warnings);
            String value = CollectionExportSupport.resolve(variable.value, resolver, resolve);
            BrunoFormatSupport.appendDictionaryEntry(out, "  ", variable.enabled ? "" : "~", key,
                    value != null ? value : "", context + " variable", warnings);
        }
        out.append("}\n\n");
    }

    private static void appendScripts(StringBuilder out,
                                      ApiRequest request,
                                      VariableResolver resolver,
                                      boolean resolve,
                                      List<String> warnings) {
        appendScriptPhase(out, "script:pre-request", request, ScriptPhase.PRE_REQUEST,
                request.preRequestScripts, resolver, resolve, warnings);
        appendScriptPhase(out, "script:post-response", request, ScriptPhase.POST_RESPONSE,
                request.postResponseScripts, resolver, resolve, warnings);
        appendScriptPhase(out, "tests", request, ScriptPhase.TEST,
                null, resolver, resolve, warnings);
    }

    private static void appendScriptPhase(StringBuilder out, String blockName,
                                          ApiRequest request, ScriptPhase phase,
                                          List<ApiRequest.Script> legacy,
                                          VariableResolver resolver, boolean resolve,
                                          List<String> warnings) {
        List<String> sources = new ArrayList<>();
        boolean hasBlocks = false;
        if (request.scriptBlocks != null) {
            List<ScriptBlock> sorted = new ArrayList<>(request.scriptBlocks);
            sorted.sort(Comparator.comparingInt(block -> block != null ? block.order : Integer.MAX_VALUE));
            for (ScriptBlock block : sorted) {
                if (block == null || block.phase != phase) continue;
                hasBlocks = true;
                if (!block.enabled) {
                    ExportWarningSupport.add(warnings, "Bruno export omitted disabled " + phase
                            + " script for request '" + ExportWarningSupport.label(request.name) + "'.");
                } else if (block.source != null && !block.source.isBlank()) {
                    sources.add(CollectionExportSupport.resolve(block.source, resolver, resolve));
                }
            }
        }
        if (!hasBlocks && legacy != null) {
            for (ApiRequest.Script script : legacy) {
                if (script != null && script.exec != null && !script.exec.isBlank()) {
                    sources.add(CollectionExportSupport.resolve(script.exec, resolver, resolve));
                }
            }
        }
        sources.removeIf(source -> source == null || source.isBlank());
        if (!sources.isEmpty()) BrunoFormatSupport.appendTextBlock(out, blockName, String.join("\n\n", sources),
                phase + " script for request '" + ExportWarningSupport.label(request.name) + "'", warnings);
    }

    private static void appendAuthBlock(StringBuilder out,
                                        ApiRequest.Auth auth,
                                        String context,
                                        VariableResolver resolver,
                                        boolean resolve,
                                        List<String> warnings) throws IOException {
        if (auth == null || auth.type == null || auth.type.isBlank() || "inherit".equalsIgnoreCase(auth.type)) return;
        String type = auth.type.toLowerCase(Locale.ROOT);
        if ("noauth".equals(type)) type = "none";
        if (!SUPPORTED_AUTH.contains(type)) {
            ExportWarningSupport.add(warnings, "Bruno export represented unsupported auth type '"
                    + ExportWarningSupport.label(type) + "' as none for " + context + ".");
            out.append("auth:none {\n}\n\n");
            return;
        }
        out.append("auth:").append(type).append(" {\n");
        if (!"none".equals(type)) {
            Map<String, String> mapped = canonicalAuthProperties(type, auth.properties, context, warnings);
            Set<String> keys = BrunoFormatSupport.newKeySet();
            for (Map.Entry<String, String> entry : mapped.entrySet()) {
                String key = BrunoFormatSupport.uniqueRenderedKey(entry.getKey(), keys, context + " auth", warnings);
                String value = CollectionExportSupport.resolve(entry.getValue(), resolver, resolve);
                BrunoFormatSupport.appendDictionaryEntry(out, "  ", "", key, value != null ? value : "",
                        context + " auth property", warnings);
            }
        }
        out.append("}\n\n");
    }

    private static Map<String, String> canonicalAuthProperties(String type,
                                                               Map<String, String> source,
                                                               String context,
                                                               List<String> warnings) {
        Map<String, String> input = source != null ? source : Map.of();
        LinkedHashMap<String, String> mapped = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();
        switch (type) {
            case "basic" -> {
                mapAuth(mapped, consumed, input, "username", "username", "user");
                mapAuth(mapped, consumed, input, "password", "password", "pass");
            }
            case "bearer" -> mapAuth(mapped, consumed, input, "token", "token", "value", "access_token");
            case "apikey" -> {
                mapAuth(mapped, consumed, input, "key", "key", "name", "keyname");
                mapAuth(mapped, consumed, input, "value", "value", "keyvalue", "token");
                String placement = first(input, "in", "placement", "location");
                consumed.addAll(List.of("in", "placement", "location"));
                mapped.put("placement", "queryParams".equalsIgnoreCase(placement) ? "query"
                        : placement != null ? placement : "header");
            }
            case "oauth2" -> {
                mapAuth(mapped, consumed, input, "grant_type", "grantType", "grant_type", "grant");
                mapAuth(mapped, consumed, input, "access_token_url", "accessTokenUrl", "access_token_url");
                mapAuth(mapped, consumed, input, "authorization_url", "authorizationUrl", "authorization_url");
                mapAuth(mapped, consumed, input, "callback_url", "redirectUri", "callback_url", "redirect_uri");
                mapAuth(mapped, consumed, input, "client_id", "clientId", "client_id");
                mapAuth(mapped, consumed, input, "client_secret", "clientSecret", "client_secret");
                mapAuth(mapped, consumed, input, "scope", "scope");
                mapAuth(mapped, consumed, input, "username", "username");
                mapAuth(mapped, consumed, input, "password", "password");
                mapAuth(mapped, consumed, input, "access_token", "accessToken", "access_token", "token");
            }
            default -> { }
        }
        Set<String> unsupported = new java.util.TreeSet<>();
        for (String key : input.keySet()) {
            if (key != null && !consumed.contains(key)) unsupported.add(key);
        }
        if (!unsupported.isEmpty()) {
            ExportWarningSupport.add(warnings, "Bruno export omitted unsupported auth properties for "
                    + context + ": " + String.join(", ", unsupported) + ".");
        }
        return mapped;
    }

    private static void mapAuth(Map<String, String> target, Set<String> consumed,
                                Map<String, String> source, String targetKey, String... candidates) {
        String value = first(source, candidates);
        for (String candidate : candidates) consumed.add(candidate);
        if (value != null) target.put(targetKey, value);
    }

    private static String first(Map<String, String> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) return source.get(key);
        }
        return null;
    }

    private static AuthSelection requestAuthSelection(ApiRequest request, List<String> warnings) {
        String mode = AuthInheritanceResolver.normalizeAuthOverrideMode(request.authOverrideMode, request);
        if ("inherit".equals(mode)) return new AuthSelection(null, null);
        if ("none".equals(mode) || request.authExplicitlyDisabled) return new AuthSelection("none", noneAuth());
        ApiRequest.Auth auth = request.explicitAuth != null ? request.explicitAuth : request.auth;
        String type = auth != null && auth.type != null ? auth.type.toLowerCase(Locale.ROOT) : "none";
        if ("noauth".equals(type)) type = "none";
        if (!SUPPORTED_AUTH.contains(type)) {
            ExportWarningSupport.add(warnings, "Bruno export represented unsupported auth type '"
                    + ExportWarningSupport.label(type) + "' as none for request '"
                    + ExportWarningSupport.label(request.name) + "'.");
            return new AuthSelection("none", noneAuth());
        }
        return new AuthSelection(type, auth);
    }

    private static String bodySelector(ApiRequest.Body body) {
        if (body == null || body.mode == null || body.mode.isBlank() || "none".equalsIgnoreCase(body.mode)) return "none";
        return switch (body.mode.toLowerCase(Locale.ROOT)) {
            case "graphql" -> "graphql";
            case "urlencoded" -> "form-urlencoded";
            case "formdata" -> "multipart-form";
            case "file" -> "file";
            case "raw" -> {
                String contentType = body.contentType != null ? body.contentType.toLowerCase(Locale.ROOT) : "";
                if (contentType.contains("json")) yield "json";
                if (contentType.contains("xml")) yield "xml";
                yield "text";
            }
            default -> "text";
        };
    }

    private static void validateMethod(String method, String context) throws IOException {
        if (method == null || method.isEmpty()) throw new IOException("Bruno export requires an HTTP method for " + context + ".");
        String separators = "()<>@,;:\\\"/[]?={} \t";
        for (int i = 0; i < method.length(); i++) {
            char ch = method.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f || separators.indexOf(ch) >= 0 || Character.isSurrogate(ch)) {
                throw new IOException("Bruno export rejected an invalid HTTP method for " + context + ".");
            }
        }
    }

    private static boolean hasEnabledHeader(List<ApiRequest.Header> headers, String name) {
        if (headers == null) return false;
        for (ApiRequest.Header header : headers) {
            if (header != null && !header.disabled && header.key != null
                    && name.equalsIgnoreCase(header.key.trim())) return true;
        }
        return false;
    }

    private static String allocateName(String raw,
                                       String fallback,
                                       String extension,
                                       Set<String> used,
                                       List<String> warnings) {
        String base = archiveSegment(raw, fallback);
        if (raw != null && !raw.equals(base)) {
            ExportWarningSupport.add(warnings, "Bruno export normalized an unsafe archive entry label '"
                    + ExportWarningSupport.label(raw) + "'.");
        }
        String candidate = extension != null ? base + extension : base;
        int suffix = 2;
        while (RESERVED_NAMES.contains(candidate.toLowerCase(Locale.ROOT))
                || !used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "_" + suffix++ + (extension != null ? extension : "");
        }
        if (!(extension != null ? base + extension : base).equals(candidate)) {
            ExportWarningSupport.add(warnings, "Bruno export renamed a colliding archive entry '"
                    + ExportWarningSupport.label(raw) + "'.");
        }
        return candidate;
    }

    private static String archiveSegment(String raw, String fallback) {
        String value = ExportFileNamePolicy.sanitizeBaseName(raw != null ? raw : fallback);
        if (value.equals(".") || value.equals("..") || value.isBlank()) value = fallback;
        value = value.replace('/', '_').replace('\\', '_').replace(':', '_');
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f || Character.isSurrogate(ch)) safe.append('_');
            else safe.append(ch);
        }
        return safe.toString().isBlank() ? fallback : safe.toString();
    }

    private static void validateEntries(Set<String> paths) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.indexOf('\\') >= 0 || path.matches("^[A-Za-z]:.*")) {
                throw new IOException("Bruno export generated an unsafe archive entry.");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IOException("Bruno export generated an unsafe archive entry.");
                }
                for (int i = 0; i < segment.length(); i++) {
                    char ch = segment.charAt(i);
                    if (ch < 0x20 || ch == 0x7f || Character.isSurrogate(ch)) {
                        throw new IOException("Bruno export generated an unsafe archive entry.");
                    }
                }
            }
            if (!seen.add(path.toLowerCase(Locale.ROOT))) {
                throw new IOException("Bruno export generated duplicate archive entries.");
            }
        }
    }

    private static byte[] gsonBytes(Map<String, Object> value) {
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                .toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] textBytes(String value) {
        return (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
    }

    private static ApiRequest.Auth noneAuth() {
        ApiRequest.Auth auth = new ApiRequest.Auth();
        auth.type = "none";
        return auth;
    }

    private static ApiRequest dummyRequestForFolder(String folderPath) {
        ApiRequest request = new ApiRequest();
        request.name = "__folder__";
        request.path = folderPath;
        return request;
    }

    private record AuthSelection(String selector, ApiRequest.Auth auth) { }
}
