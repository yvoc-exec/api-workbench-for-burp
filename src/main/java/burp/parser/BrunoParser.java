package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.script.*;

/**
 * Parser for Bruno collections (.bru files).
 * Bruno stores each request as a separate .bru file in a folder structure.
 */
public class BrunoParser implements CollectionParser {

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
        // Or a single .bru file
        return file.getName().endsWith(".bru");
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        ApiCollection collection = new ApiCollection();
        collection.format = "bruno";
        collection.name = file.getName().replace(".bru", "");

        if (file.isDirectory()) {
            // Walk directory recursively
            Files.walk(file.toPath()).forEach(path -> {
                if (path.toString().endsWith(".bru")) {
                    try {
                        String relativePath = file.toPath().relativize(path.getParent()).toString();
                        ApiRequest req = parseBruFile(path.toFile(), relativePath);
                        if (req != null) {
                            req.sourceCollection = collection.name;
                            collection.requests.add(req);
                        }
                    } catch (Exception e) {
                        // Skip malformed files
                    }
                }
            });
        } else {
            ApiRequest req = parseBruFile(file, "");
            if (req != null) {
                req.sourceCollection = collection.name;
                collection.requests.add(req);
            }
        }

        // Load bruno.json for collection metadata if present
        File brunoJson = new File(file.isDirectory() ? file : file.getParentFile(), "bruno.json");
        if (brunoJson.exists()) {
            try (FileReader reader = new FileReader(brunoJson)) {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("name")) {
                    collection.name = obj.get("name").getAsString();
                }
            }
        }

        return collection;
    }

    private ApiRequest parseBruFile(File file, String folderPath) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            ApiRequest req = new ApiRequest();
            req.name = file.getName().replace(".bru", "");
            req.path = folderPath.isEmpty() ? req.name : folderPath + "/" + req.name;

            // Parse meta block
            Pattern metaPattern = Pattern.compile("meta\\s*\\{\\s*name:\\s*(.+?)\\s*type:\\s*(.+?)\\s*seq:\\s*(\\d+)\\s*\\}");
            Matcher metaMatcher = metaPattern.matcher(content);
            if (metaMatcher.find()) {
                req.name = metaMatcher.group(1).trim();
                req.sequenceOrder = Integer.parseInt(metaMatcher.group(3).trim());
            }

            // Parse method from first line
            Pattern methodPattern = Pattern.compile("^(get|post|put|delete|patch|head|options|trace)\\s*([^\\{\n]*?)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            Matcher methodMatcher = methodPattern.matcher(content);
            if (methodMatcher.find()) {
                req.method = methodMatcher.group(1).toUpperCase();
                // URL might be on same line (rare) or inside the { url: ... } block
                String sameLineUrl = methodMatcher.group(2).trim();
                if (!sameLineUrl.isEmpty()) {
                    req.url = sameLineUrl;
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
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).trim();
                        String value = line.substring(colonIdx + 1).trim();
                        req.headers.add(new ApiRequest.Header(key, value));
                    }
                }
            }

            // Parse body
            Pattern bodyPattern = Pattern.compile("body\\s*[:]?\\s*(?:\\{([^}]+)\\}|(none|json|xml|text|graphql|form|multipart))?", Pattern.CASE_INSENSITIVE);
            Matcher bodyMatcher = bodyPattern.matcher(content);
            if (bodyMatcher.find()) {
                String bodyBlock = bodyMatcher.group(1).trim();
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
            }

            // Parse auth (basic auth in Bruno)
            Pattern authPattern = Pattern.compile("auth\\s*\\{\\s*basic\\s*\\{\\s*username:\\s*(.+?)\\s*password:\\s*(.+?)\\s*\\}\\s*\\}");
            Matcher authMatcher = authPattern.matcher(content);
            if (authMatcher.find()) {
                req.auth = new ApiRequest.Auth();
                req.auth.type = "basic";
                req.auth.properties.put("username", authMatcher.group(1).trim());
                req.auth.properties.put("password", authMatcher.group(2).trim());
            }

            // Parse vars (pre-request variables)
            Pattern varsPattern = Pattern.compile("vars\\s*\\{([^}]+)\\}");
            Matcher varsMatcher = varsPattern.matcher(content);
            if (varsMatcher.find()) {
                String varsBlock = varsMatcher.group(1);
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
            Pattern assertPattern = Pattern.compile("assert\\s*\\{([^}]+)\\}");
            Matcher assertMatcher = assertPattern.matcher(content);
            if (assertMatcher.find()) {
                String assertBlock = assertMatcher.group(1);
                req.postResponseScripts.add(new ApiRequest.Script("js", assertBlock));
            }

            // Parse script:pre-request
            Pattern preScriptPattern = Pattern.compile("script:pre-request\\s*\\{([^}]+)\\}");
            Matcher preScriptMatcher = preScriptPattern.matcher(content);
            if (preScriptMatcher.find()) {
                String script = preScriptMatcher.group(1);
                // Normalize Bruno script to standard JS
                script = normalizeBrunoScript(script);
                req.preRequestScripts.add(new ApiRequest.Script("js", script));
            }

            // Parse script:post-response
            Pattern postScriptPattern = Pattern.compile("script:post-response\\s*\\{([^}]+)\\}");
            Matcher postScriptMatcher = postScriptPattern.matcher(content);
            if (postScriptMatcher.find()) {
                String script = postScriptMatcher.group(1);
                script = normalizeBrunoScript(script);
                req.postResponseScripts.add(new ApiRequest.Script("js", script));
            }

            return req;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize Bruno script syntax to standard JavaScript.
     * Converts bru.setVar() to pm.environment.set() for cross-compatibility.
     */
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
    public String[] getSupportedExtensions() { return new String[]{"bru"}; }
}
