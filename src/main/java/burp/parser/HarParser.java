package burp.parser;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/**
 * Parser for HAR (HTTP Archive) files.
 */
public class HarParser implements CollectionParser {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public boolean canParse(File file) {
        if (!file.getName().endsWith(".har")) return false;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            return obj.has("log") && obj.getAsJsonObject("log").has("entries");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApiCollection parse(File file) throws Exception {
        JsonObject obj;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            obj = JsonParser.parseReader(reader).getAsJsonObject();
        }

        ApiCollection collection = new ApiCollection();
        collection.format = "har";
        collection.name = file.getName().replace(".har", "");

        JsonObject log = obj.getAsJsonObject("log");
        JsonArray entries = log.getAsJsonArray("entries");

        for (JsonElement e : entries) {
            JsonObject entry = e.getAsJsonObject();
            JsonObject request = entry.getAsJsonObject("request");
            if (request == null) continue;

            ApiRequest req = new ApiRequest();
            req.sourceCollection = collection.name;
            req.name = getString(request, "url", "Unnamed");
            // Truncate URL for name
            if (req.name.length() > 80) req.name = req.name.substring(0, 80) + "...";
            req.method = getString(request, "method", "GET");
            req.url = getString(request, "url", "");

            // Headers
            if (request.has("headers") && request.get("headers").isJsonArray()) {
                for (JsonElement h : request.getAsJsonArray("headers")) {
                    JsonObject header = h.getAsJsonObject();
                    req.headers.add(new ApiRequest.Header(
                        getString(header, "name", ""),
                        getString(header, "value", "")
                    ));
                }
            }

            // Body
            if (request.has("postData") && request.get("postData").isJsonObject()) {
                JsonObject postData = request.getAsJsonObject("postData");
                req.body = new ApiRequest.Body();
                req.body.mode = "raw";
                req.body.raw = getString(postData, "text", "");
                req.body.contentType = getString(postData, "mimeType", "text/plain");

                if (postData.has("params") && postData.get("params").isJsonArray()) {
                    String mimeType = req.body.contentType;
                    if (mimeType.contains("form-urlencoded")) {
                        req.body.mode = "urlencoded";
                        for (JsonElement p : postData.getAsJsonArray("params")) {
                            JsonObject param = p.getAsJsonObject();
                            req.body.urlencoded.add(new ApiRequest.Body.FormField(
                                getString(param, "name", ""),
                                getString(param, "value", "")
                            ));
                        }
                    } else if (mimeType.contains("multipart")) {
                        req.body.mode = "formdata";
                        for (JsonElement p : postData.getAsJsonArray("params")) {
                            JsonObject param = p.getAsJsonObject();
                            req.body.formdata.add(new ApiRequest.Body.FormField(
                                getString(param, "name", ""),
                                getString(param, "value", "")
                            ));
                        }
                    }
                }
            }

            collection.requests.add(req);
        }

        return collection;
    }

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return defaultValue;
    }

    @Override
    public String getFormatName() { return "HAR"; }

    @Override
    public String[] getSupportedExtensions() { return new String[]{"har"}; }
}
