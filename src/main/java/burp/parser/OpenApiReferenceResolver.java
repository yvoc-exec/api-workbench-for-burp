package burp.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parse-operation-local, bounded resolver for internal and safe local OpenAPI references. */
public final class OpenApiReferenceResolver {
    static final long MAX_DOCUMENT_BYTES = 5L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;
    static final int MAX_REFERENCED_DOCUMENTS = 64;
    static final int MAX_REFERENCE_DEPTH = 32;
    static final int MAX_REFERENCE_RESOLUTIONS = 512;
    static final int MAX_YAML_NESTING_DEPTH = 100;
    static final int MAX_YAML_ALIASES = 50;
    static final int MAX_YAML_CODE_POINTS = 5_000_000;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final File rootFile;
    private final List<String> warnings;
    private final Map<Path, Map<String, Object>> documents = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Path> owners = new IdentityHashMap<>();
    private Path rootPath;
    private Path rootDirectory;
    private long totalBytes;
    private int externalDocuments;
    private int resolutions;

    public OpenApiReferenceResolver(File rootFile, List<String> warnings) {
        this.rootFile = rootFile;
        this.warnings = warnings;
    }

    public Map<String, Object> loadRoot() throws IOException {
        if (rootFile == null) throw new IOException("OpenAPI root file is required");
        rootPath = rootFile.toPath().toRealPath();
        if (!Files.isRegularFile(rootPath)) throw new IOException("OpenAPI root must be a regular file");
        rootDirectory = rootPath.getParent();
        Map<String, Object> root = loadDocument(rootPath, false);
        if (root == null) throw new IOException("OpenAPI document root must be a map");
        return root;
    }

    public Map<String, Object> resolveObject(Map<String, Object> candidate,
                                             String expectedKind,
                                             String context) {
        if (candidate == null) return null;
        Object resolved = resolveNode(candidate, ownerOf(candidate), new ArrayDeque<>(), 0, context);
        if (!(resolved instanceof Map<?, ?> map)) {
            warn("blocked or unresolved reference", context);
            return null;
        }
        Map<String, Object> result = castMap(map);
        if (!matchesKind(result, expectedKind)) {
            warn("blocked or unresolved reference (wrong object kind)", context);
            return null;
        }
        return result;
    }

    public Object resolveSchemaNode(Object candidate, String context) {
        if (candidate == null) return null;
        if (candidate instanceof Boolean) return candidate;
        Object resolved = resolveNode(candidate, ownerOf(candidate), new ArrayDeque<>(), 0, context);
        if (resolved instanceof Map<?, ?> || resolved instanceof Boolean) return resolved;
        warn("blocked or unresolved reference (schema)", context);
        return null;
    }

    public Object resolveExampleNode(Object candidate, String context) {
        if (candidate == null) return null;
        Object resolved = resolveNode(candidate, ownerOf(candidate), new ArrayDeque<>(), 0, context);
        if (resolved instanceof Map<?, ?>) return resolved;
        warn("blocked or unresolved reference (example)", context);
        return null;
    }

    private Object resolveNode(Object candidate,
                               Path owner,
                               Deque<String> chain,
                               int depth,
                               String context) {
        if (!(candidate instanceof Map<?, ?> raw)) return candidate;
        Map<String, Object> map = castMap(raw);
        Object rawRef = map.get("$ref");
        if (!(rawRef instanceof String ref) || ref.isBlank()) return map;
        if (depth >= MAX_REFERENCE_DEPTH || resolutions >= MAX_REFERENCE_RESOLUTIONS) {
            warn("reference cycle or limit", context);
            return null;
        }
        ResolutionTarget target = target(ref, owner, context);
        if (target == null) return null;
        String identity = target.document + "#" + target.pointer;
        if (chain.contains(identity)) {
            warn("reference cycle or limit", context);
            return null;
        }
        resolutions++;
        chain.addLast(identity);
        Object selected = pointer(target.root, target.pointer);
        if (selected == Missing.INSTANCE) {
            chain.removeLast();
            warn("blocked or unresolved reference (missing target)", context);
            return null;
        }
        Object resolved = resolveNode(selected, target.document, chain, depth + 1, context);
        chain.removeLast();
        if (resolved instanceof Map<?, ?> resolvedMap && map.size() > 1) {
            Map<String, Object> merged = new LinkedHashMap<>(castMap(resolvedMap));
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!"$ref".equals(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
            }
            indexOwners(merged, target.document);
            return merged;
        }
        return resolved;
    }

    private ResolutionTarget target(String ref, Path owner, String context) {
        try {
            int hash = ref.indexOf('#');
            String documentPart = hash >= 0 ? ref.substring(0, hash) : ref;
            String fragment = hash >= 0 ? ref.substring(hash + 1) : "";
            Path document = owner != null ? owner : rootPath;
            Map<String, Object> root;
            if (documentPart.isEmpty()) {
                root = documents.get(document);
            } else {
                URI uri = URI.create(documentPart);
                if (uri.isAbsolute() || uri.getScheme() != null || uri.getAuthority() != null || uri.getQuery() != null
                        || documentPart.startsWith("/") || documentPart.startsWith("\\")
                        || documentPart.matches("^[A-Za-z]:.*")) {
                    warn("blocked or unresolved reference", context);
                    return null;
                }
                String relative = uri.getPath();
                if (relative == null || relative.isBlank()) {
                    warn("blocked or unresolved reference", context);
                    return null;
                }
                String lower = relative.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
                    warn("blocked or unresolved reference (unsupported extension)", context);
                    return null;
                }
                Path base = document != null && document.getParent() != null ? document.getParent() : rootDirectory;
                Path requested = base.resolve(relative).normalize();
                if (!requested.startsWith(rootDirectory) || !Files.exists(requested)) {
                    warn("blocked or unresolved reference", context);
                    return null;
                }
                Path real = requested.toRealPath();
                if (!real.startsWith(rootDirectory) || !Files.isRegularFile(real)) {
                    warn("blocked or unresolved reference", context);
                    return null;
                }
                document = real;
                root = documents.get(document);
                if (root == null) root = loadDocument(document, true);
            }
            if (root == null || (!fragment.isEmpty() && !fragment.startsWith("/"))) {
                warn("blocked or unresolved reference", context);
                return null;
            }
            return new ResolutionTarget(document, root, fragment);
        } catch (Exception e) {
            warn("blocked or unresolved reference", context);
            return null;
        }
    }

    private Map<String, Object> loadDocument(Path path, boolean referenced) throws IOException {
        long size = Files.size(path);
        if (size > MAX_DOCUMENT_BYTES || size < 0 || totalBytes + size > MAX_TOTAL_BYTES) {
            warn("reference cycle or limit (document size)", "document");
            return null;
        }
        if (referenced && ++externalDocuments > MAX_REFERENCED_DOCUMENTS) {
            warn("reference cycle or limit (document count)", "document");
            return null;
        }
        byte[] bytes = Files.readAllBytes(path);
        totalBytes += bytes.length;
        Object parsed;
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) {
                parsed = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Object.class);
            } else if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
                options.setNestingDepthLimit(MAX_YAML_NESTING_DEPTH);
                options.setCodePointLimit(MAX_YAML_CODE_POINTS);
                parsed = new Yaml(new SafeConstructor(options)).load(new ByteArrayInputStream(bytes));
            } else {
                warn("blocked or unresolved reference (unsupported extension)", "document");
                return null;
            }
        } catch (RuntimeException e) {
            if (referenced) {
                warn("blocked or unresolved reference (invalid document)", "document");
                return null;
            }
            throw new IOException("Invalid OpenAPI document", e);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            if (referenced) warn("blocked or unresolved reference (wrong root kind)", "document");
            return null;
        }
        Map<String, Object> map = castMap(raw);
        documents.put(path, map);
        indexOwners(map, path);
        return map;
    }

    private Object pointer(Object root, String pointer) {
        if (pointer == null || pointer.isEmpty()) return root;
        Object current = root;
        String[] tokens = pointer.substring(1).split("/", -1);
        for (String raw : tokens) {
            String token = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) return Missing.INSTANCE;
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(token);
                    if (index < 0 || index >= list.size()) return Missing.INSTANCE;
                    current = list.get(index);
                } catch (NumberFormatException e) {
                    return Missing.INSTANCE;
                }
            } else {
                return Missing.INSTANCE;
            }
        }
        return current;
    }

    private void indexOwners(Object value, Path owner) {
        if (value == null || owners.containsKey(value)) return;
        if (value instanceof Map<?, ?> map) {
            owners.put(value, owner);
            for (Object child : map.values()) indexOwners(child, owner);
        } else if (value instanceof List<?> list) {
            owners.put(value, owner);
            for (Object child : list) indexOwners(child, owner);
        }
    }

    private Path ownerOf(Object value) {
        Path owner = owners.get(value);
        return owner != null ? owner : rootPath;
    }

    private boolean matchesKind(Map<String, Object> object, String expectedKind) {
        if (expectedKind == null || expectedKind.isBlank() || "schema".equalsIgnoreCase(expectedKind)) return true;
        return switch (expectedKind.toLowerCase(Locale.ROOT)) {
            case "parameter" -> object.containsKey("name") && object.containsKey("in");
            case "requestbody", "request body" -> object.containsKey("content") || object.containsKey("schema");
            case "example" -> object.containsKey("value") || object.containsKey("externalValue");
            default -> true;
        };
    }

    private void warn(String family, String context) {
        OpenApiWarningSupport.add(warnings, family + (context == null || context.isBlank() ? "" : ": " + context));
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        if (raw instanceof LinkedHashMap<?, ?>) {
            @SuppressWarnings("unchecked") Map<String, Object> direct = (Map<String, Object>) raw;
            return direct;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private enum Missing { INSTANCE }
    private record ResolutionTarget(Path document, Map<String, Object> root, String pointer) {}
}
