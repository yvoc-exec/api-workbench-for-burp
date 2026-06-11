package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.parser.VariableResolver;
import burp.ui.tree.RequestTreePathService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
            sb.append("  ").append(variable.key).append(": ");
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
            sb.append("  ").append(entry.getKey()).append(": ");
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
        sb.append(method).append(" {\n");
        sb.append("  url: ").append(CollectionExportSupport.resolve(request.url, resolver, resolve) != null ? CollectionExportSupport.resolve(request.url, resolver, resolve) : "").append("\n");
        sb.append("}\n\n");

        if (request.headers != null && !request.headers.isEmpty()) {
            sb.append("headers {\n");
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.key == null || header.key.isBlank()) {
                    continue;
                }
                if (CollectionExportSupport.isTransportHeader(header.key)) {
                    continue;
                }
                String prefix = header.disabled ? "~" : "";
                sb.append("  ").append(prefix).append(header.key).append(": ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(header.value, resolver, resolve) != null
                                ? CollectionExportSupport.resolve(header.value, resolver, resolve)
                                : ""))
                        .append("\n");
            }
            sb.append("}\n\n");
        }

        if (request.body != null && request.body.mode != null && !"none".equalsIgnoreCase(request.body.mode)) {
            sb.append("body {\n");
            String bodyText = renderBodyText(request.body, resolver, resolve);
            if (!bodyText.isBlank()) {
                for (String line : bodyText.split("\\R", -1)) {
                    sb.append("  ").append(line).append("\n");
                }
            }
            sb.append("}\n\n");
        } else {
            sb.append("body: none\n\n");
        }

        String authBlock = renderAuthBlock(request, resolver, resolve);
        if (authBlock != null && !authBlock.isBlank()) {
            sb.append(authBlock).append("\n\n");
        }

        if (request.preRequestScripts != null && !request.preRequestScripts.isEmpty()) {
            sb.append("script:pre-request {\n");
            for (ApiRequest.Script script : request.preRequestScripts) {
                if (script == null || script.exec == null) {
                    continue;
                }
                String text = CollectionExportSupport.resolve(script.exec, resolver, resolve);
                if (text == null || text.isBlank()) {
                    continue;
                }
                for (String line : text.split("\\R", -1)) {
                    sb.append("  ").append(line).append("\n");
                }
            }
            sb.append("}\n\n");
        }

        if (request.postResponseScripts != null && !request.postResponseScripts.isEmpty()) {
            sb.append("script:post-response {\n");
            for (ApiRequest.Script script : request.postResponseScripts) {
                if (script == null || script.exec == null) {
                    continue;
                }
                String text = CollectionExportSupport.resolve(script.exec, resolver, resolve);
                if (text == null || text.isBlank()) {
                    continue;
                }
                for (String line : text.split("\\R", -1)) {
                    sb.append("  ").append(line).append("\n");
                }
            }
            sb.append("}\n\n");
        }

        if (request.variables != null && !request.variables.isEmpty()) {
            sb.append("vars {\n");
            for (ApiRequest.Variable variable : request.variables) {
                if (variable == null || variable.key == null || variable.key.isBlank()) {
                    continue;
                }
                sb.append("  ").append(variable.key).append(": ")
                        .append(BrunoEnvironmentExporterHelper.escapeValue(CollectionExportSupport.resolve(variable.value, resolver, resolve) != null
                                ? CollectionExportSupport.resolve(variable.value, resolver, resolve)
                                : ""))
                        .append("\n");
            }
            sb.append("}\n");
        }

        writeTextEntry(zip, filePath, sb.toString());
    }

    private static String renderBodyText(ApiRequest.Body body, VariableResolver resolver, boolean resolve) {
        if (body == null || body.mode == null) {
            return "";
        }
        String mode = body.mode.toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "raw" -> CollectionExportSupport.resolve(body.raw, resolver, resolve) != null ? CollectionExportSupport.resolve(body.raw, resolver, resolve) : "";
            case "urlencoded" -> renderFields(body.urlencoded, resolver, resolve);
            case "formdata" -> renderFields(body.formdata, resolver, resolve);
            case "graphql" -> {
                String query = CollectionExportSupport.resolve(body.graphql != null ? body.graphql.query : "", resolver, resolve);
                String vars = CollectionExportSupport.resolve(body.graphql != null ? body.graphql.variables : "", resolver, resolve);
                yield "{\"query\":" + BrunoEnvironmentExporterHelper.jsonString(query) + ",\"variables\":" + BrunoEnvironmentExporterHelper.jsonString(vars) + "}";
            }
            default -> "";
        };
    }

    private static String renderFields(List<ApiRequest.Body.FormField> fields, VariableResolver resolver, boolean resolve) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ApiRequest.Body.FormField field : fields) {
            if (field == null || field.key == null || field.key.isBlank()) {
                continue;
            }
            sb.append(field.key).append('=');
            sb.append(CollectionExportSupport.resolve(field.value, resolver, resolve) != null ? CollectionExportSupport.resolve(field.value, resolver, resolve) : "");
            if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                sb.append("  # file");
                if (field.filePath != null && !field.filePath.isBlank()) {
                    sb.append(": ").append(CollectionExportSupport.resolve(field.filePath, resolver, resolve));
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
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
