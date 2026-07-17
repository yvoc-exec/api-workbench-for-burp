package burp.parser;

import burp.utils.OpenApiMetadataSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic OpenAPI example selection and bounded schema value generation. */
public final class OpenApiValueSupport {
    private static final int MAX_SCHEMA_DEPTH = 32;
    private static final int MAX_OBJECT_PROPERTIES = 100;
    private static final int MAX_ARRAY_ITEMS = 10;

    private OpenApiValueSupport() {}

    public static final class SelectedValue {
        public final Object value;
        public final String type;
        public final String format;
        public final String source;

        SelectedValue(Object value, String type, String format, String source) {
            this.value = value;
            this.type = type;
            this.format = format;
            this.source = source;
        }
    }

    public static SelectedValue selectParameterValue(Map<String, Object> parameter,
                                                     OpenApiReferenceResolver references,
                                                     List<String> warnings,
                                                     String context) {
        if (parameter == null) return new SelectedValue(null, null, null, "none");
        Object schema = parameter.get("schema");
        if (schema == null && parameter.containsKey("type")) schema = swaggerSchema(parameter);
        Map<String, Object> content = parameter.get("content") instanceof Map<?, ?> rawContent
                ? castMap(rawContent) : Map.of();
        List<String> orderedContent = orderedMediaTypes(content);
        Map<String, Object> selectedMedia = !orderedContent.isEmpty()
                && content.get(orderedContent.get(0)) instanceof Map<?, ?> media
                ? castMap(media) : null;
        Object effectiveSchema = selectedMedia != null ? selectedMedia.get("schema") : schema;
        Object resolvedSchema = references != null
                ? references.resolveSchemaNode(effectiveSchema, context + " schema") : effectiveSchema;
        String type = effectiveType(resolvedSchema);
        String format = effectiveFormat(resolvedSchema);
        if (parameter.containsKey("example")) return selected(parameter.get("example"), type, format, "parameter.example");
        ValueChoice examples = chooseExample(parameter.get("examples"), references, warnings, context);
        if (examples.present) return selected(examples.value, type, format, "parameter.examples");
        if (selectedMedia != null) {
            if (orderedContent.size() > 1) OpenApiWarningSupport.add(
                    warnings, "multiple parameter content types: " + String.join(", ", orderedContent));
            SelectedValue selected = selectMediaValue(selectedMedia, references, warnings, context);
            return new SelectedValue(selected.value, selected.type, selected.format,
                    "parameter.content." + selected.source);
        }
        if (parameter.containsKey("x-example")) return selected(parameter.get("x-example"), type, format, "parameter.x-example");
        if (parameter.containsKey("default")) return selected(parameter.get("default"), type, format, "parameter.default");
        return selectSchemaValue(resolvedSchema, references, warnings, context, "schema");
    }

    public static SelectedValue selectMediaValue(Map<String, Object> media,
                                                 OpenApiReferenceResolver references,
                                                 List<String> warnings,
                                                 String context) {
        if (media == null) return new SelectedValue(null, null, null, "none");
        Object schema = media.get("schema");
        Object resolvedSchema = references != null
                ? references.resolveSchemaNode(schema, context + " schema") : schema;
        String type = effectiveType(resolvedSchema);
        String format = effectiveFormat(resolvedSchema);
        if (media.containsKey("example")) return selected(media.get("example"), type, format, "media.example");
        ValueChoice examples = chooseExample(media.get("examples"), references, warnings, context);
        if (examples.present) return selected(examples.value, type, format, "media.examples");
        return selectSchemaValue(resolvedSchema, references, warnings, context, "schema");
    }

    public static Object generateSchemaValue(Object schema,
                                             OpenApiReferenceResolver references,
                                             List<String> warnings,
                                             String context) {
        return generate(schema, references, warnings, context, 0, new IdentityHashMap<>());
    }

    public static String effectiveType(Object schema) {
        if (!(schema instanceof Map<?, ?> raw)) return schema instanceof Boolean ? "object" : null;
        Map<String, Object> map = castMap(raw);
        Object type = map.get("type");
        if (type instanceof String text) return text;
        if (type instanceof List<?> types) {
            for (Object item : types) if (item instanceof String text && !"null".equals(text)) return text;
            if (types.contains("null")) return "null";
        }
        if (map.containsKey("properties") || map.containsKey("allOf")) return "object";
        if (map.containsKey("items")) return "array";
        return null;
    }

    public static String effectiveFormat(Object schema) {
        if (!(schema instanceof Map<?, ?> map)) return null;
        Object format = map.get("format");
        return format != null ? String.valueOf(format) : null;
    }

    public static List<String> orderedMediaTypes(Map<String, Object> content) {
        List<String> result = new ArrayList<>();
        if (content != null) result.addAll(content.keySet());
        result.sort(Comparator.comparingInt(OpenApiValueSupport::mediaRank)
                .thenComparing(value -> value.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));
        return result;
    }

    private static SelectedValue selectSchemaValue(Object schema,
                                                   OpenApiReferenceResolver references,
                                                   List<String> warnings,
                                                   String context,
                                                   String sourcePrefix) {
        Object resolved = references != null ? references.resolveSchemaNode(schema, context) : schema;
        String type = effectiveType(resolved);
        String format = effectiveFormat(resolved);
        if (resolved instanceof Map<?, ?> raw) {
            Map<String, Object> map = castMap(raw);
            if (map.containsKey("example")) return selected(map.get("example"), type, format, sourcePrefix + ".example");
            if (map.containsKey("default")) return selected(map.get("default"), type, format, sourcePrefix + ".default");
            if (map.get("enum") instanceof List<?> values && !values.isEmpty()) {
                return selected(values.get(0), type, format, sourcePrefix + ".enum");
            }
        }
        return selected(generateSchemaValue(resolved, references, warnings, context), type, format, sourcePrefix + ".generated");
    }

    private static Object generate(Object schema,
                                   OpenApiReferenceResolver references,
                                   List<String> warnings,
                                   String context,
                                   int depth,
                                   IdentityHashMap<Object, Boolean> active) {
        if (depth >= MAX_SCHEMA_DEPTH) {
            OpenApiWarningSupport.add(warnings, "generated-value truncation: schema depth");
            return null;
        }
        Object resolved = references != null ? references.resolveSchemaNode(schema, context) : schema;
        if (resolved instanceof Boolean bool) return bool ? new LinkedHashMap<>() : null;
        if (!(resolved instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = castMap(raw);
        if (active.put(raw, Boolean.TRUE) != null) {
            OpenApiWarningSupport.add(warnings, "reference cycle or limit: schema generation");
            return null;
        }
        try {
            if (map.containsKey("example")) return map.get("example");
            if (map.containsKey("default")) return map.get("default");
            if (map.get("enum") instanceof List<?> values && !values.isEmpty()) return values.get(0);

            if (map.get("allOf") instanceof List<?> allOf) {
                Map<String, Object> merged = new LinkedHashMap<>();
                for (Object branch : allOf) {
                    Object value = generate(branch, references, warnings, context, depth + 1, active);
                    if (value instanceof Map<?, ?> object) {
                        for (Map.Entry<?, ?> entry : object.entrySet()) {
                            String key = String.valueOf(entry.getKey());
                            if (merged.containsKey(key)) OpenApiWarningSupport.add(warnings, "schema branch approximation: allOf collision " + OpenApiWarningSupport.label(key));
                            merged.put(key, entry.getValue());
                        }
                    }
                }
                return merged;
            }
            for (String union : List.of("oneOf", "anyOf")) {
                if (map.get(union) instanceof List<?> branches) {
                    OpenApiWarningSupport.add(warnings, "schema branch approximation: " + union + " first resolvable branch");
                    for (Object branch : branches) {
                        Object resolvedBranch = references != null ? references.resolveSchemaNode(branch, context) : branch;
                        if (resolvedBranch != null) return generate(resolvedBranch, references, warnings, context, depth + 1, active);
                    }
                    return null;
                }
            }

            String type = effectiveType(map);
            if (type == null) type = "object";
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "null" -> null;
                case "boolean" -> Boolean.TRUE;
                case "integer" -> numericSample(map, true);
                case "number" -> numericSample(map, false);
                case "array" -> generateArray(map, references, warnings, context, depth, active);
                case "object" -> generateObject(map, references, warnings, context, depth, active);
                default -> stringSample(effectiveFormat(map));
            };
        } finally {
            active.remove(raw);
        }
    }

    private static Object generateObject(Map<String, Object> schema,
                                         OpenApiReferenceResolver references,
                                         List<String> warnings,
                                         String context,
                                         int depth,
                                         IdentityHashMap<Object, Boolean> active) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(schema.get("properties") instanceof Map<?, ?> rawProperties)) return result;
        Map<String, Object> properties = castMap(rawProperties);
        Set<String> required = new LinkedHashSet<>();
        if (schema.get("required") instanceof List<?> list) for (Object item : list) if (item != null) required.add(String.valueOf(item));
        List<String> order = new ArrayList<>();
        for (String key : required) if (properties.containsKey(key)) order.add(key);
        for (String key : properties.keySet()) if (!order.contains(key)) order.add(key);
        int added = 0;
        for (String key : order) {
            if (added >= MAX_OBJECT_PROPERTIES) {
                OpenApiWarningSupport.add(warnings, "generated-value truncation: object properties");
                break;
            }
            Object property = properties.get(key);
            Object resolved = references != null ? references.resolveSchemaNode(property, context) : property;
            if (resolved instanceof Map<?, ?> propertyMap && Boolean.TRUE.equals(propertyMap.get("readOnly"))) continue;
            result.put(key, generate(resolved, references, warnings, context, depth + 1, active));
            added++;
        }
        return result;
    }

    private static Object generateArray(Map<String, Object> schema,
                                        OpenApiReferenceResolver references,
                                        List<String> warnings,
                                        String context,
                                        int depth,
                                        IdentityHashMap<Object, Boolean> active) {
        int count = 1;
        if (schema.get("minItems") instanceof Number min) count = Math.max(0, min.intValue());
        if (count > MAX_ARRAY_ITEMS) {
            count = MAX_ARRAY_ITEMS;
            OpenApiWarningSupport.add(warnings, "generated-value truncation: array items");
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(generate(schema.get("items"), references, warnings, context, depth + 1, active));
        return values;
    }

    private static Object numericSample(Map<String, Object> schema, boolean integer) {
        Number base = schema.get("minimum") instanceof Number minimum ? minimum : null;
        double value = base != null ? base.doubleValue() : 1d;
        if (schema.get("multipleOf") instanceof Number multiple && multiple.doubleValue() != 0d) {
            value = Math.ceil(value / multiple.doubleValue()) * multiple.doubleValue();
        }
        return integer ? (long) value : value;
    }

    private static String stringSample(String format) {
        if (format == null) return "string";
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "password" -> "password";
            case "email", "idn-email" -> "user@example.com";
            case "uuid" -> "00000000-0000-4000-8000-000000000000";
            case "date" -> "2000-01-01";
            case "date-time" -> "2000-01-01T00:00:00Z";
            case "time" -> "00:00:00Z";
            case "uri", "url", "uri-reference" -> "https://example.com";
            case "hostname", "idn-hostname" -> "example.com";
            case "ipv4" -> "192.0.2.1";
            case "ipv6" -> "2001:db8::1";
            case "byte", "binary" -> "";
            default -> "string";
        };
    }

    private static ValueChoice chooseExample(Object examples,
                                             OpenApiReferenceResolver references,
                                             List<String> warnings,
                                             String context) {
        if (!(examples instanceof Map<?, ?> raw)) return ValueChoice.absent();
        Map<String, Object> map = castMap(raw);
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            Object node = map.get(key);
            Object resolved = references != null ? references.resolveExampleNode(node, context) : node;
            if (resolved instanceof Map<?, ?> example) {
                if (example.containsKey("value")) return new ValueChoice(true, example.get("value"));
                if (example.containsKey("externalValue")) OpenApiWarningSupport.add(warnings, "externalValue not fetched: " + OpenApiWarningSupport.label(context));
            }
        }
        return ValueChoice.absent();
    }

    private static SelectedValue selected(Object value, String type, String format, String source) {
        return new SelectedValue(value, type, format, source);
    }

    private static Map<String, Object> swaggerSchema(Map<String, Object> parameter) {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String key : List.of("type", "format", "items", "properties", "required", "enum", "default", "example", "nullable", "allOf", "oneOf", "anyOf")) {
            if (parameter.containsKey(key)) schema.put(key, parameter.get(key));
        }
        return schema;
    }

    private static int mediaRank(String mediaType) {
        String value = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).trim();
        if ("application/json".equals(value)) return 0;
        if (value.startsWith("application/") && value.endsWith("+json")) return 1;
        if ("application/x-www-form-urlencoded".equals(value)) return 2;
        if ("multipart/form-data".equals(value)) return 3;
        if ("text/plain".equals(value)) return 4;
        if ("application/octet-stream".equals(value)) return 5;
        if (value.startsWith("text/")) return 6;
        if (value.startsWith("application/")) return 7;
        return 8;
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private record ValueChoice(boolean present, Object value) {
        static ValueChoice absent() { return new ValueChoice(false, null); }
    }
}
