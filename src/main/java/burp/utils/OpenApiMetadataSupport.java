package burp.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless canonical JSON storage for format-specific OpenAPI source structures. */
public final class OpenApiMetadataSupport {
    public static final String SOURCE_VERSION = "openapi.sourceVersion";
    public static final String REF = "openapi.ref";
    public static final String SCHEMA = "openapi.schema";
    public static final String RESOLVED_SCHEMA = "openapi.resolvedSchema";
    public static final String CONTENT = "openapi.content";
    public static final String RESOLVED_CONTENT = "openapi.resolvedContent";
    public static final String EXAMPLES = "openapi.examples";
    public static final String EXAMPLE = "openapi.example";
    public static final String VALUE_SOURCE = "openapi.valueSource";
    public static final String EXTENSIONS = "openapi.extensions";
    public static final String DEPRECATED = "openapi.deprecated";
    public static final String ALLOW_EMPTY_VALUE = "openapi.allowEmptyValue";
    public static final String ORIGINAL_REQUIRED = "openapi.originalRequired";
    public static final String REQUEST_BODY_CONTENT = "openapi.requestBody.content";
    public static final String REQUEST_BODY_RESOLVED_CONTENT = "openapi.requestBody.resolvedContent";
    public static final String REQUEST_BODY_SELECTED_MEDIA_TYPE = "openapi.requestBody.selectedMediaType";
    public static final String REQUEST_BODY_EXTENSIONS = "openapi.requestBody.extensions";
    public static final String REQUEST_BODY_ENCODING = "openapi.requestBody.encoding";
    public static final String OPERATION_EXTENSIONS = "openapi.operation.extensions";
    public static final String OPERATION_CALLBACKS = "openapi.operation.callbacks";
    public static final String DOCUMENT_EXTENSIONS = "openapi.document.extensions";
    public static final String DOCUMENT_SERVERS = "openapi.document.servers";
    public static final String DOCUMENT_TAGS = "openapi.document.tags";
    public static final String DOCUMENT_INFO = "openapi.document.info";
    public static final String DOCUMENT_EXTERNAL_DOCS = "openapi.document.externalDocs";
    public static final String DOCUMENT_COMPONENTS = "openapi.document.components";
    public static final String DOCUMENT_SWAGGER_STRUCTURES = "openapi.swagger2.documentStructures";
    public static final String PATH_ITEM_SERVERS = "openapi.pathItem.servers";
    public static final String PATH_ITEM_EXTENSIONS = "openapi.pathItem.extensions";
    public static final String PATH_ITEM_STRUCTURES = "openapi.pathItem.structures";
    public static final String OPERATION_SERVERS = "openapi.operation.servers";
    public static final String EFFECTIVE_SERVER_LEVEL = "openapi.effectiveServerLevel";
    public static final String PATH_TEMPLATE = "openapi.pathTemplate";
    public static final String OPERATION_TAGS = "openapi.operation.tags";
    public static final String OPERATION_EXTERNAL_DOCS = "openapi.operation.externalDocs";
    public static final String OPERATION_DEPRECATED = "openapi.operation.deprecated";
    public static final String OPERATION_RESPONSES = "openapi.operation.responses";
    public static final String OPERATION_SWAGGER_STRUCTURES = "openapi.swagger2.operationStructures";
    public static final String SWAGGER_COLLECTION_FORMAT = "openapi.swagger2.collectionFormat";
    public static final String IGNORED_STANDARD_HEADER = "openapi.ignoredStandardHeader";
    public static final String EXPLICIT_STYLE = "openapi.explicitStyle";
    public static final String EXPLICIT_EXPLODE = "openapi.explicitExplode";
    public static final String EXPLICIT_REQUIRED = "openapi.explicitRequired";
    public static final String MEDIA_EXTENSIONS = "openapi.media.extensions";
    public static final String SCHEMA_EXTENSIONS = "openapi.schema.extensions";
    public static final String RETAINED_FORM_DATA = "openapi.swagger2.formData";
    public static final String UNSUPPORTED = "openapi.unsupported";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping().serializeNulls().create();

    private OpenApiMetadataSupport() {}

    public static Map<String, String> copy(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    public static String canonicalJson(Object value) {
        return GSON.toJson(deepCopy(value));
    }

    public static Object parseCanonicalJson(String value) {
        if (value == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return GSON.fromJson(parsed, Object.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> parseObject(String value) {
        Object parsed = parseCanonicalJson(value);
        if (!(parsed instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
        }
        return result;
    }

    public static List<Object> parseArray(String value) {
        Object parsed = parseCanonicalJson(value);
        if (!(parsed instanceof List<?> list)) return new ArrayList<>();
        List<Object> result = new ArrayList<>();
        for (Object item : list) result.add(deepCopy(item));
        return result;
    }

    public static void putCanonical(Map<String, String> metadata, String key, Object value) {
        if (metadata != null && key != null) metadata.put(key, canonicalJson(value));
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }
}
