package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.ui.tree.RequestTreePathService;
import burp.utils.RequestParameterSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BrunoCollectionExporter {
    private BrunoCollectionExporter() {
    }

    public static void write(ApiCollection collection, CollectionExportOptions options, OutputStream outputStream, List<String> warnings) throws IOException {
        boolean resolve = options != null && options.resolveVariablesUsingActiveEnvironment;
        EnvironmentProfile activeEnvironment = options != null ? options.activeEnvironment : null;
        Map<String, String> exportOnly = options != null ? options.exportOnlyVariables : Map.of();

        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
            String rootName = ExportFileNamePolicy.sanitizeBaseName(collection != null && collection.name != null ? collection.name : "collection");
            String rootDir = rootName + "/";
            putDirectory(zip, rootDir);

            if (collection != null) {
                writeBrunoJson(zip, rootDir, collection, activeEnvironment, exportOnly, resolve, warnings);
                writeCollectionVarsFile(zip, rootDir, collection, activeEnvironment, exportOnly, resolve, warnings);

                CollectionExportTree.FolderNode tree = CollectionExportTree.build(collection);
                writeFolders(zip, rootDir, collection, tree, activeEnvironment, exportOnly, resolve, warnings);
            }
        }
    }

    private static void writeBrunoJson(ZipOutputStream zip,
                                       String rootDir,
                                       ApiCollection collection,
                                       EnvironmentProfile activeEnvironment,
                                       Map<String, String> exportOnly,
                                       boolean resolve,
                                       List<String> warnings) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", collection.name != null ? collection.name : "Collection");
        if (collection.environment != null && !collection.environment.isEmpty()) {
            root.put("vars", collection.environment);
            root.put("env", collection.environment);
        }
            writeJsonEntry(zip, rootDir + "bruno.json", root);
    }

    private static void writeCollectionVarsFile(ZipOutputStream zip,
                                                String rootDir,
                                                ApiCollection collection,
                                                EnvironmentProfile activeEnvironment,
                                                Map<String, String> exportOnly,
                                                boolean resolve,
                                                List<String> warnings) throws IOException {
        if (collection.variables == null || collection.variables.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("vars {\n");
        VariableResolver resolver = CollectionExportSupport.buildResolver(collection, null, activeEnvironment, exportOnly);
        for (ApiRequest.Variable variable : collection.variables) {
            if (variable == null || variable.key == null || variable.key.isBlank()) {
                continue;
            }
            sb.append("  ").append(variable.enabled ? "" : "~")
                    .append(renderBruDictionaryKey(variable.key)).append(": ");
            sb.append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(variable.value, resolver, resolve) != null
                    ? CollectionExportSupport.resolve(variable.value, resolver, resolve)
                    : "")).append("\n");
        }
        sb.append("}\n");
        writeTextEntry(zip, rootDir + "_collection.bru", sb.toString());
    }

    private static void writeFolders(ZipOutputStream zip,
                                     String rootDir,
                                     ApiCollection collection,
                                     CollectionExportTree.FolderNode node,
                                     EnvironmentProfile activeEnvironment,
                                     Map<String, String> exportOnly,
                                     boolean resolve,
                                     List<String> warnings) throws IOException {
        for (CollectionExportTree.FolderNode child : node.children.values()) {
            String folderDir = rootDir + child.path + "/";
            putDirectory(zip, folderDir);
            writeFolderVars(zip, folderDir, collection, child, activeEnvironment, exportOnly, resolve, warnings);
            writeFolders(zip, rootDir, collection, child, activeEnvironment, exportOnly, resolve, warnings);
        }

        int index = 1;
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (ApiRequest request : node.requests) {
            if (request == null) {
                continue;
            }
            VariableResolver resolver = CollectionExportSupport.buildResolver(collection, request, activeEnvironment, exportOnly);
            String fileNameBase = ExportFileNamePolicy.sanitizeBaseName(request.name != null ? request.name : "request");
            int count = nameCounts.getOrDefault(fileNameBase, 0) + 1;
            nameCounts.put(fileNameBase, count);
            if (count > 1) {
                fileNameBase = fileNameBase + "_" + count;
            }
            String filePath = rootDir + normalizeFolderPath(node.path) + (node.path == null || node.path.isBlank() ? "" : "/") + fileNameBase + ".bru";
            writeRequestFile(zip, filePath, request, resolver, resolve, index++, warnings);
        }
    }

    private static void writeFolderVars(ZipOutputStream zip,
                                        String folderDir,
                                        ApiCollection collection,
                                        CollectionExportTree.FolderNode node,
                                        EnvironmentProfile activeEnvironment,
                                        Map<String, String> exportOnly,
                                        boolean resolve,
                                        List<String> warnings) throws IOException {
        String folderPath = RequestTreePathService.normalizeFolderPath(node.path);
        Map<String, String> vars = collection.folderVars != null ? collection.folderVars.get(folderPath) : null;
        if (vars == null || vars.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("vars {\n");
        VariableResolver resolver = CollectionExportSupport.buildResolver(collection, dummyRequestForFolder(folderPath), activeEnvironment, exportOnly);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            sb.append("  ").append(renderBruDictionaryKey(entry.getKey())).append(": ");
            sb.append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(entry.getValue(), resolver, resolve) != null
                    ? CollectionExportSupport.resolve(entry.getValue(), resolver, resolve)
                    : "")).append("\n");
        }
        sb.append("}\n");
        writeTextEntry(zip, folderDir + "_folder.bru", sb.toString());
    }

    private static void writeRequestFile(ZipOutputStream zip,
                                         String filePath,
                                         ApiRequest request,
                                         VariableResolver resolver,
                                         boolean resolve,
                                         int seq,
                                         List<String> warnings) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("meta {\n");
        sb.append("  name: ").append(request.name != null ? request.name : "Request").append("\n");
        sb.append("  type: http\n");
        sb.append("  seq: ").append(request.sequenceOrder > 0 ? request.sequenceOrder : seq).append("\n");
        sb.append("}\n\n");

        String method = request.method != null ? request.method.toLowerCase(java.util.Locale.ROOT) : "get";
        String exportedUrl = RequestParameterSupport.materializeUrl(
                request.url, request.parameters, resolve ? resolver : null);
        sb.append(method).append(" {\n");
        sb.append("  url: ").append(exportedUrl != null ? exportedUrl : "").append("\n");
        sb.append("}\n\n");

        appendParameterBlocks(sb, request, resolver, resolve, warnings);

        boolean synthesizeContentType = request.body != null
                && request.body.contentType != null && !request.body.contentType.isBlank()
                && !hasEnabledHeader(request.headers, "Content-Type");
        if ((request.headers != null && !request.headers.isEmpty()) || synthesizeContentType) {
            sb.append("headers {\n");
            for (ApiRequest.Header header : request.headers != null ? request.headers : List.<ApiRequest.Header>of()) {
                if (header == null || header.key == null || header.key.isBlank()) {
                    continue;
                }
                if (CollectionExportSupport.isTransportHeader(header.key)) {
                    continue;
                }
                String prefix = header.disabled ? "~" : "";
                sb.append("  ").append(prefix).append(renderBruDictionaryKey(header.key)).append(": ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(header.value, resolver, resolve) != null
                                ? CollectionExportSupport.resolve(header.value, resolver, resolve)
                                : ""))
                        .append("\n");
            }
            if (synthesizeContentType) {
                sb.append("  Content-Type: ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(request.body.contentType)).append("\n");
            }
            sb.append("}\n\n");
        }

        appendTypedBody(sb, request, resolver, resolve, warnings);

        String authBlock = renderAuthBlock(request, resolver, resolve);
        if (authBlock != null && !authBlock.isBlank()) {
            sb.append(authBlock).append("\n\n");
        }

        appendScriptBlock(sb, "script:pre-request", request, ScriptPhase.PRE_REQUEST,
                request.preRequestScripts, resolver, resolve, warnings);
        appendScriptBlock(sb, "script:post-response", request, ScriptPhase.POST_RESPONSE,
                request.postResponseScripts, resolver, resolve, warnings);
        appendScriptBlock(sb, "test", request, ScriptPhase.TEST,
                null, resolver, resolve, warnings);

        if (request.variables != null && !request.variables.isEmpty()) {
            sb.append("vars {\n");
            for (ApiRequest.Variable variable : request.variables) {
                if (variable == null || variable.key == null || variable.key.isBlank()) {
                    continue;
                }
                sb.append("  ").append(variable.enabled ? "" : "~")
                        .append(renderBruDictionaryKey(variable.key)).append(": ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(variable.value, resolver, resolve) != null
                                ? CollectionExportSupport.resolve(variable.value, resolver, resolve)
                                : ""))
                        .append("\n");
            }
            sb.append("}\n");
        }

        writeTextEntry(zip, filePath, sb.toString());
    }

    private static void appendParameterBlocks(StringBuilder sb, ApiRequest request,
                                              VariableResolver resolver, boolean resolve,
                                              List<String> warnings) {
        List<ApiRequest.Parameter> query = new ArrayList<>();
        List<ApiRequest.Parameter> path = new ArrayList<>();
        if (request.parameters != null) {
            for (ApiRequest.Parameter parameter : request.parameters) {
                if (parameter == null) continue;
                if (parameter.isQuery()) query.add(parameter);
                else if ("path".equalsIgnoreCase(parameter.location)) path.add(parameter);
            }
        }
        appendParameterBlock(sb, "params:query", query, request, resolver, resolve, warnings, true);
        appendParameterBlock(sb, "params:path", path, request, resolver, resolve, warnings, false);
    }

    private static void appendParameterBlock(StringBuilder sb, String name,
                                             List<ApiRequest.Parameter> parameters,
                                             ApiRequest request, VariableResolver resolver,
                                             boolean resolve, List<String> warnings,
                                             boolean query) {
        List<ApiRequest.Parameter> emitted = new ArrayList<>();
        for (ApiRequest.Parameter parameter : parameters) {
            if (parameter.valuePresent || (!parameter.valuePresent && parameter.disabled)) {
                emitted.add(parameter);
            }
        }
        if (emitted.isEmpty()) return;
        sb.append(name).append(" {\n");
        for (ApiRequest.Parameter parameter : emitted) {
            if (query && !parameter.valuePresent && parameter.disabled) {
                addWarning(warnings, "Bruno export for request '" + safeRequestName(request)
                        + "' cannot preserve disabled bare query parameter '" + safeKey(parameter.key)
                        + "'; exported it as disabled explicit-empty.");
            }
            String key = CollectionExportSupport.resolve(parameter.key, resolver, resolve);
            String value = CollectionExportSupport.resolve(parameter.value, resolver, resolve);
            sb.append("  ").append(parameter.disabled ? "~" : "")
                    .append(renderBruDictionaryKey(key)).append(": ")
                    .append(BrunoEnvironmentExporterHelper.escapeValue(value != null ? value : ""))
                    .append("\n");
        }
        sb.append("}\n\n");
    }

    private static void appendTypedBody(StringBuilder sb, ApiRequest request,
                                        VariableResolver resolver, boolean resolve,
                                        List<String> warnings) {
        ApiRequest.Body body = request.body;
        if (body == null || body.mode == null || "none".equalsIgnoreCase(body.mode)) return;
        String mode = body.mode.toLowerCase(java.util.Locale.ROOT);
        if ("graphql".equals(mode)) {
            appendContentBlock(sb, "body:graphql", CollectionExportSupport.resolve(
                    body.graphql != null ? body.graphql.query : "", resolver, resolve));
            appendContentBlock(sb, "body:graphql:vars", CollectionExportSupport.resolve(
                    body.graphql != null ? body.graphql.variables : "{}", resolver, resolve));
            return;
        }
        if ("urlencoded".equals(mode)) {
            appendFieldBody(sb, "body:form-urlencoded", body.urlencoded, false,
                    request, resolver, resolve, warnings);
            return;
        }
        if ("formdata".equals(mode)) {
            appendFieldBody(sb, "body:multipart-form", body.formdata, true,
                    request, resolver, resolve, warnings);
            return;
        }
        String type = "body:text";
        String contentType = body.contentType != null ? body.contentType.toLowerCase(java.util.Locale.ROOT) : "";
        if (contentType.contains("json")) type = "body:json";
        else if (contentType.contains("xml")) type = "body:xml";
        appendContentBlock(sb, type, CollectionExportSupport.resolve(body.raw, resolver, resolve));
    }

    private static void appendFieldBody(StringBuilder sb, String name,
                                        List<ApiRequest.Body.FormField> fields, boolean multipart,
                                        ApiRequest request, VariableResolver resolver,
                                        boolean resolve, List<String> warnings) {
        sb.append(name).append(" {\n");
        if (fields != null) {
            for (ApiRequest.Body.FormField field : fields) {
                if (field == null || field.key == null) continue;
                String key = CollectionExportSupport.resolve(field.key, resolver, resolve);
                sb.append("  ").append(field.disabled ? "~" : "")
                        .append(renderBruDictionaryKey(key)).append(": ");
                boolean file = multipart && (field.fileUpload || "file".equalsIgnoreCase(field.type));
                if (file && field.filePath != null && !field.filePath.isBlank()) {
                    String path = CollectionExportSupport.resolve(field.filePath, resolver, resolve);
                    sb.append("@file(").append(path != null ? path : "").append(')');
                } else {
                    if (file) {
                        addWarning(warnings, "Bruno export for request '" + safeRequestName(request)
                                + "' retained file field '" + safeKey(field.key)
                                + "' as text because it has no usable path.");
                    }
                    String value = CollectionExportSupport.resolve(field.value, resolver, resolve);
                    sb.append(BrunoEnvironmentExporterHelper.escapeValue(value != null ? value : ""));
                }
                sb.append("\n");
            }
        }
        sb.append("}\n\n");
    }

    private static void appendContentBlock(StringBuilder sb, String name, String source) {
        sb.append(name).append(" {\n");
        appendIndented(sb, source != null ? source : "");
        sb.append("}\n\n");
    }

    private static void appendScriptBlock(StringBuilder sb, String blockName,
                                          ApiRequest request, ScriptPhase phase,
                                          List<ApiRequest.Script> legacy,
                                          VariableResolver resolver, boolean resolve,
                                          List<String> warnings) {
        List<String> sources = new ArrayList<>();
        boolean hasPhaseBlocks = false;
        if (request.scriptBlocks != null) {
            List<ScriptBlock> sorted = new ArrayList<>(request.scriptBlocks);
            sorted.sort(Comparator.comparingInt(block -> block != null ? block.order : Integer.MAX_VALUE));
            for (ScriptBlock block : sorted) {
                if (block == null || block.phase != phase) continue;
                hasPhaseBlocks = true;
                if (!block.enabled) {
                    addWarning(warnings, "Bruno export omitted disabled " + phase
                            + " script for request '" + safeRequestName(request) + "'.");
                } else if (block.source != null && !block.source.isBlank()) {
                    sources.add(CollectionExportSupport.resolve(block.source, resolver, resolve));
                }
            }
        }
        if (!hasPhaseBlocks && legacy != null) {
            for (ApiRequest.Script script : legacy) {
                if (script != null && script.exec != null && !script.exec.isBlank()) {
                    sources.add(CollectionExportSupport.resolve(script.exec, resolver, resolve));
                }
            }
        }
        sources.removeIf(source -> source == null || source.isBlank());
        if (sources.isEmpty()) return;
        sb.append(blockName).append(" {\n");
        appendIndented(sb, String.join("\n\n", sources));
        sb.append("}\n\n");
    }

    private static void appendIndented(StringBuilder sb, String source) {
        for (String line : source.split("\\R", -1)) {
            sb.append("  ").append(line).append("\n");
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

    private static String renderBruDictionaryKey(String key) {
        String value = key != null ? key : "";
        if (value.matches("[A-Za-z0-9_.-]+") && !value.startsWith("~")) {
            return value;
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static String safeRequestName(ApiRequest request) {
        return request != null && request.name != null ? request.name.replaceAll("[\\r\\n]", " ") : "Request";
    }

    private static String safeKey(String key) {
        return key != null ? key.replaceAll("[\\r\\n]", " ") : "";
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings != null && warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static String renderAuthBlock(ApiRequest request, VariableResolver resolver, boolean resolve) {
        if (request == null) {
            return null;
        }
        if (request.auth == null || request.auth.type == null || request.auth.type.isBlank()) {
            return null;
        }
        if (!request.hasAuth()) {
            if (request.authExplicitlyDisabled || "none".equalsIgnoreCase(request.authOverrideMode)) {
                return "auth {\n  mode: none\n}";
            }
            return null;
        }
        String type = request.auth.type.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        sb.append("auth {\n");
        sb.append("  mode: ").append(type).append("\n");
        if (request.auth.properties != null) {
            for (Map.Entry<String, String> entry : request.auth.properties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                sb.append("  ").append(entry.getKey()).append(": ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(entry.getValue(), resolver, resolve) != null
                                ? CollectionExportSupport.resolve(entry.getValue(), resolver, resolve)
                                : ""))
                        .append("\n");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static void writeJsonEntry(ZipOutputStream zip, String path, Map<String, Object> data) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        byte[] bytes = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(data).getBytes(StandardCharsets.UTF_8);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeTextEntry(ZipOutputStream zip, String path, String text) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void putDirectory(ZipOutputStream zip, String dir) throws IOException {
        if (dir == null || dir.isBlank()) {
            return;
        }
        String normalized = dir.endsWith("/") ? dir : dir + "/";
        ZipEntry entry = new ZipEntry(normalized);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static String normalizeFolderPath(String path) {
        return RequestTreePathService.normalizeFolderPath(path);
    }

    private static ApiRequest dummyRequestForFolder(String folderPath) {
        ApiRequest request = new ApiRequest();
        request.name = "__folder__";
        request.path = folderPath;
        return request;
    }

    private static final class BrunoEnvironmentExporterHelper {
        private BrunoEnvironmentExporterHelper() {}

        static String escapeValue(String value) {
            if (value == null) {
                return "";
            }
            boolean needsQuotes = value.contains(":")
                    || value.contains("#")
                    || value.contains("\"")
                    || value.contains("'")
                    || value.contains("\\")
                    || value.contains("{")
                    || value.contains("}")
                    || value.contains(" ")
                    || value.contains("\t")
                    || value.contains("\n")
                    || value.contains("\r");
            String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
            return needsQuotes ? "\"" + escaped + "\"" : escaped;
        }

        static String jsonString(String value) {
            return new com.google.gson.JsonPrimitive(value != null ? value : "").toString();
        }
    }
}
