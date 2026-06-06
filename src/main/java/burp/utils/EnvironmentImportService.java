package burp.utils;

import burp.models.EnvironmentProfile;
import com.google.gson.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnvironmentImportService {
    private EnvironmentImportService() {
    }

    public static List<EnvironmentProfile> importEnvironment(File file) throws IOException {
        if (file == null) {
            throw new IOException("Environment file is required.");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Environment file not found: " + file.getAbsolutePath());
        }

        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            throw new IOException("Environment file is empty: " + file.getName());
        }

        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".bru")) {
            Map<String, String> values = parseBrunoVarsBlock(raw);
            if (values.isEmpty()) {
                throw new IOException("No environment variables found in Bruno file: " + file.getName());
            }
            return List.of(fromKeyValueMap(stripExtension(file.getName()), "bruno", file.getName(), values));
        }

        if (lowerName.endsWith(".env")) {
            Map<String, String> values = parseEnvFile(raw);
            if (values.isEmpty()) {
                throw new IOException("No environment variables found in .env file: " + file.getName());
            }
            return List.of(fromKeyValueMap(stripExtension(file.getName()), "dotenv", file.getName(), values));
        }

        if (looksLikeJson(raw)) {
            return importJsonEnvironment(file.getName(), raw);
        }

        throw new IOException("Unsupported environment file format: " + file.getName());
    }

    public static EnvironmentProfile fromKeyValueMap(String name, String sourceFormat, String sourceFileName, Map<String, String> values) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = name;
        profile.sourceFormat = sourceFormat;
        profile.sourceFileName = sourceFileName;
        profile.variables.clear();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                profile.variables.put(key, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static List<EnvironmentProfile> importJsonEnvironment(String fileName, String raw) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (Exception e) {
            throw new IOException("Invalid JSON environment file: " + fileName, e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Unsupported environment JSON: expected object at root in " + fileName);
        }

        JsonObject root = parsed.getAsJsonObject();
        List<EnvironmentProfile> profiles = new ArrayList<>();

        if (root.has("resources") && root.get("resources").isJsonArray()) {
            for (JsonElement resourceElem : root.getAsJsonArray("resources")) {
                if (resourceElem == null || !resourceElem.isJsonObject()) {
                    continue;
                }
                JsonObject resource = resourceElem.getAsJsonObject();
                String type = getString(resource, "_type");
                if (!"environment".equalsIgnoreCase(type)) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                if (resource.has("data") && resource.get("data").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : resource.getAsJsonObject("data").entrySet()) {
                        if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                            continue;
                        }
                        values.put(entry.getKey(), stringifyJsonValue(entry.getValue()));
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }
                String name = firstNonBlank(getString(resource, "name"), getString(resource, "_id"), "Environment");
                profiles.add(fromKeyValueMap(name, "insomnia", fileName, values));
            }
            if (profiles.isEmpty()) {
                throw new IOException("No Insomnia environment resources found in " + fileName);
            }
            return profiles;
        }

        if (root.has("variables") && root.get("variables").isJsonObject()) {
            EnvironmentProfile profile = fromApiWorkbenchExport(fileName, root);
            return List.of(profile);
        }

        if (root.has("values") && root.get("values").isJsonArray()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (JsonElement valueElem : root.getAsJsonArray("values")) {
                if (valueElem == null || !valueElem.isJsonObject()) {
                    continue;
                }
                JsonObject value = valueElem.getAsJsonObject();
                boolean enabled = !value.has("enabled") || value.get("enabled").isJsonNull() || value.get("enabled").getAsBoolean();
                String key = getString(value, "key");
                if (!enabled || key == null || key.isBlank()) {
                    continue;
                }
                values.put(key, stringifyJsonValue(value.has("value") ? value.get("value") : JsonNull.INSTANCE));
            }
            if (values.isEmpty()) {
                throw new IOException("No enabled Postman environment values found in " + fileName);
            }
            return List.of(fromKeyValueMap(firstNonBlank(getString(root, "name"), stripExtension(fileName), "Environment"), "postman", fileName, values));
        }

        if (root.has("variables") && root.get("variables").isJsonArray()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (JsonElement valueElem : root.getAsJsonArray("variables")) {
                if (valueElem == null || !valueElem.isJsonObject()) {
                    continue;
                }
                JsonObject value = valueElem.getAsJsonObject();
                boolean enabled = !value.has("enabled") || value.get("enabled").isJsonNull() || value.get("enabled").getAsBoolean();
                String key = firstNonBlank(getString(value, "key"), getString(value, "name"));
                if (!enabled || key == null || key.isBlank()) {
                    continue;
                }
                values.put(key, value.has("value") && !value.get("value").isJsonNull() ? stringifyJsonValue(value.get("value")) : "");
            }
            if (values.isEmpty()) {
                throw new IOException("No enabled environment variables found in " + fileName);
            }
            return List.of(fromKeyValueMap(firstNonBlank(getString(root, "name"), stripExtension(fileName), "Environment"), "json-variables", fileName, values));
        }

        Map<String, String> primitiveValues = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if ("oauth2".equals(entry.getKey()) && entry.getValue() != null && entry.getValue().isJsonObject()) {
                continue;
            }
            if ("variables".equals(entry.getKey()) && entry.getValue() != null && entry.getValue().isJsonObject()) {
                continue;
            }
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            primitiveValues.put(entry.getKey(), stringifyJsonValue(entry.getValue()));
        }
        if (primitiveValues.isEmpty()) {
            throw new IOException("Unsupported or empty JSON environment file: " + fileName);
        }
        return List.of(fromKeyValueMap(stripExtension(fileName), "json-object", fileName, primitiveValues));
    }

    private static EnvironmentProfile fromApiWorkbenchExport(String fileName, JsonObject root) {
        EnvironmentProfile profile = new EnvironmentProfile();
        profile.name = firstNonBlank(getString(root, "name"), stripExtension(fileName), "Environment");
        profile.sourceFormat = "api-workbench";
        profile.sourceFileName = fileName;
        profile.variables.clear();
        if (root.has("variables") && root.get("variables").isJsonObject()) {
            JsonObject values = root.getAsJsonObject("variables");
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                    continue;
                }
                profile.variables.put(entry.getKey(), stringifyJsonValue(entry.getValue()));
            }
        }
        profile.ensureDefaults();
        if (root.has("oauth2") && root.get("oauth2").isJsonObject()) {
            JsonObject oauth2 = root.getAsJsonObject("oauth2");
            if (oauth2.has("config") && oauth2.get("config").isJsonObject()) {
                profile.oauth2.config.clear();
                for (Map.Entry<String, JsonElement> entry : oauth2.getAsJsonObject("config").entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                        continue;
                    }
                    profile.oauth2.config.put(entry.getKey(), stringifyJsonValue(entry.getValue()));
                }
            }
            if (oauth2.has("outputBindings") && oauth2.get("outputBindings").isJsonObject()) {
                profile.oauth2.outputBindings.clear();
                for (Map.Entry<String, JsonElement> entry : oauth2.getAsJsonObject("outputBindings").entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isJsonNull()) {
                        continue;
                    }
                    profile.oauth2.outputBindings.put(entry.getKey(), stringifyJsonValue(entry.getValue()));
                }
            }
        }
        profile.ensureId();
        profile.ensureDefaults();
        return profile;
    }

    private static String stringifyJsonValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        return value.toString();
    }

    private static Map<String, String> parseEnvFile(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            values.put(key, trimmed.substring(eq + 1).trim());
        }
        return values;
    }

    private static Map<String, String> parseBrunoVarsBlock(String raw) {
        String varsBlock = extractBlock(raw, "vars");
        Map<String, String> values = new LinkedHashMap<>();
        if (varsBlock == null || varsBlock.isBlank()) {
            return values;
        }
        for (String line : varsBlock.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            if (key.isEmpty()) {
                continue;
            }
            values.put(key, trimmed.substring(colon + 1).trim());
        }
        return values;
    }

    private static String extractBlock(String content, String blockName) {
        if (content == null || blockName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(blockName) + "\\s*\\{", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(content);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end() - 1;
        int braceCount = 0;
        int i = start;
        for (; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    break;
                }
            }
        }
        if (braceCount != 0) {
            return null;
        }
        return content.substring(start + 1, i).trim();
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "Environment";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean looksLikeJson(String raw) {
        String trimmed = raw.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String firstNonBlank(String... values) {
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
}
