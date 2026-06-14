package burp.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public class HistoryHarExportService {
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public String export(Collection<HistoryEntry> entries) {
        JsonObject root = new JsonObject();
        JsonObject log = new JsonObject();
        log.addProperty("version", "1.2");
        JsonObject creator = new JsonObject();
        creator.addProperty("name", "API Workbench for Burp");
        creator.addProperty("version", "2.0.0");
        log.add("creator", creator);
        JsonArray array = new JsonArray();
        for (HistoryEntry entry : entries != null ? entries : List.<HistoryEntry>of()) {
            array.add(entryToHar(entry));
        }
        log.add("entries", array);
        root.add("log", log);
        return gson.toJson(root);
    }

    public void write(Collection<HistoryEntry> entries, Path output) throws IOException {
        if (output == null) {
            throw new IOException("Output path is required");
        }
        Files.createDirectories(output.getParent() != null ? output.getParent() : output.toAbsolutePath().getParent());
        Files.writeString(output, export(entries), StandardCharsets.UTF_8);
    }

    private JsonObject entryToHar(HistoryEntry entry) {
        JsonObject har = new JsonObject();
        har.addProperty("startedDateTime", entry != null && entry.timestamp != null ? entry.timestamp.toString() : Instant.EPOCH.toString());
        har.addProperty("time", entry != null ? entry.durationMillis : 0L);
        har.add("request", requestToHar(entry));
        har.add("response", responseToHar(entry));
        har.add("cache", new JsonObject());
        har.add("timings", timings(entry));
        return har;
    }

    private JsonObject requestToHar(HistoryEntry entry) {
        JsonObject request = new JsonObject();
        HistoryRequestSnapshot snapshot = entry != null ? entry.requestSnapshot : null;
        request.addProperty("method", snapshot != null && snapshot.method != null ? snapshot.method : "GET");
        request.addProperty("url", snapshot != null && snapshot.urlTemplate != null ? snapshot.urlTemplate : "");
        request.addProperty("httpVersion", "HTTP/1.1");
        JsonArray headers = new JsonArray();
        if (snapshot != null && snapshot.headersAsAuthored != null) {
            for (HistoryHeader header : snapshot.headersAsAuthored) {
                if (header == null || header.disabled || header.name == null || header.name.isBlank()) {
                    continue;
                }
                JsonObject h = new JsonObject();
                h.addProperty("name", header.name);
                h.addProperty("value", header.value != null ? header.value : "");
                headers.add(h);
            }
        }
        request.add("headers", headers);
        request.add("cookies", new JsonArray());
        request.add("queryString", new JsonArray());
        JsonObject postData = new JsonObject();
        if (snapshot != null && snapshot.bodyMode != null && !"none".equalsIgnoreCase(snapshot.bodyMode)) {
            postData.addProperty("mimeType", snapshot.bodyMode);
            postData.addProperty("text", snapshot.displayBodyText());
            request.add("postData", postData);
        }
        request.addProperty("headersSize", -1);
        request.addProperty("bodySize", -1);
        return request;
    }

    private JsonObject responseToHar(HistoryEntry entry) {
        JsonObject response = new JsonObject();
        HistoryResponseSnapshot snapshot = entry != null ? entry.responseSnapshot : null;
        response.addProperty("status", snapshot != null && snapshot.statusCode > 0 ? snapshot.statusCode : 0);
        response.addProperty("statusText", snapshot != null && snapshot.reasonPhrase != null ? snapshot.reasonPhrase : "");
        response.addProperty("httpVersion", "HTTP/1.1");
        JsonArray headers = new JsonArray();
        if (snapshot != null && snapshot.headers != null) {
            for (HistoryHeader header : snapshot.headers) {
                if (header == null || header.name == null || header.name.isBlank()) {
                    continue;
                }
                JsonObject h = new JsonObject();
                h.addProperty("name", header.name);
                h.addProperty("value", header.value != null ? header.value : "");
                headers.add(h);
            }
        }
        response.add("headers", headers);
        response.add("cookies", new JsonArray());
        JsonObject content = new JsonObject();
        content.addProperty("size", snapshot != null && snapshot.body != null ? snapshot.body.length : 0);
        content.addProperty("mimeType", snapshot != null && snapshot.mimeType != null ? snapshot.mimeType : "");
        content.addProperty("text", snapshot != null ? snapshot.bodyAsText() : "");
        response.add("content", content);
        response.addProperty("redirectURL", "");
        response.addProperty("headersSize", -1);
        response.addProperty("bodySize", -1);
        response.add("timings", timings(entry));
        return response;
    }

    private JsonObject timings(HistoryEntry entry) {
        JsonObject timings = new JsonObject();
        long duration = entry != null ? entry.durationMillis : 0L;
        timings.addProperty("send", 0);
        timings.addProperty("wait", duration);
        timings.addProperty("receive", 0);
        timings.addProperty("dns", 0);
        timings.addProperty("connect", 0);
        timings.addProperty("ssl", 0);
        timings.addProperty("blocked", 0);
        return timings;
    }
}
