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
        String entryComment = analystComment(entry);
        if (!entryComment.isBlank()) {
            har.addProperty("comment", entryComment);
        }
        har.add("_apiWorkbench", entryMetadata(entry));
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
            postData.addProperty("_truncated", snapshot.bodyTruncated);
            postData.addProperty("_originalBodyLength", snapshot.originalBodyLength);
            postData.addProperty("_storedBodyLength", snapshot.storedBodyLength);
            postData.addProperty("_fullBodySha256", snapshot.fullBodySha256 != null ? snapshot.fullBodySha256 : "");
            postData.addProperty("_truncationReason", snapshot.truncationReason != null ? snapshot.truncationReason : "");
            if (!snapshot.truncationSummary().isBlank()) {
                postData.addProperty("comment", snapshot.truncationSummary());
            }
            request.add("postData", postData);
        }
        request.addProperty("headersSize", -1);
        request.addProperty("bodySize", snapshot != null ? snapshot.originalBodyLength : -1);
        String comment = analystComment(entry);
        if (!comment.isBlank()) {
            request.addProperty("comment", comment);
        }
        request.add("_apiWorkbench", requestMetadata(entry));
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
        content.addProperty("size", snapshot != null ? snapshot.originalBodyLength : 0);
        content.addProperty("mimeType", snapshot != null && snapshot.mimeType != null ? snapshot.mimeType : "");
        content.addProperty("text", snapshot != null ? snapshot.bodyAsText() : "");
        content.addProperty("_truncated", snapshot != null && snapshot.bodyTruncated);
        content.addProperty("_originalBodyLength", snapshot != null ? snapshot.originalBodyLength : 0);
        content.addProperty("_storedBodyLength", snapshot != null ? snapshot.storedBodyLength : 0);
        content.addProperty("_fullBodySha256", snapshot != null && snapshot.fullBodySha256 != null ? snapshot.fullBodySha256 : "");
        content.addProperty("_truncationReason", snapshot != null && snapshot.truncationReason != null ? snapshot.truncationReason : "");
        if (snapshot != null && !snapshot.truncationSummary().isBlank()) {
            content.addProperty("comment", snapshot.truncationSummary());
        }
        response.add("content", content);
        response.addProperty("redirectURL", "");
        response.addProperty("headersSize", -1);
        response.addProperty("bodySize", snapshot != null ? snapshot.originalBodyLength : -1);
        String comment = analystComment(entry);
        if (!comment.isBlank()) {
            response.addProperty("comment", comment);
        }
        response.add("_apiWorkbench", responseMetadata(entry));
        response.add("timings", timings(entry));
        return response;
    }

    private JsonObject entryMetadata(HistoryEntry entry) {
        JsonObject meta = new JsonObject();
        meta.addProperty("historyId", entry != null && entry.id != null ? entry.id : "");
        meta.addProperty("collectionId", entry != null && entry.collectionId != null ? entry.collectionId : "");
        meta.addProperty("collectionName", entry != null && entry.collectionName != null ? entry.collectionName : "");
        meta.addProperty("requestId", entry != null && entry.requestId != null ? entry.requestId : "");
        meta.addProperty("requestName", entry != null && entry.requestName != null ? entry.requestName : "");
        meta.addProperty("pinned", entry != null && entry.pinned);
        meta.addProperty("notes", entry != null && entry.analystNotes != null ? entry.analystNotes : "");
        meta.addProperty("tags", entry != null && entry.tags != null ? String.join(", ", entry.tags) : "");
        meta.add("request", requestMetadata(entry));
        meta.add("response", responseMetadata(entry));
        return meta;
    }

    private JsonObject requestMetadata(HistoryEntry entry) {
        JsonObject meta = new JsonObject();
        HistoryRequestSnapshot snapshot = entry != null ? entry.requestSnapshot : null;
        meta.addProperty("bodyTruncated", snapshot != null && snapshot.bodyTruncated);
        meta.addProperty("originalBodyLength", snapshot != null ? snapshot.originalBodyLength : 0);
        meta.addProperty("storedBodyLength", snapshot != null ? snapshot.storedBodyLength : 0);
        meta.addProperty("fullBodySha256", snapshot != null && snapshot.fullBodySha256 != null ? snapshot.fullBodySha256 : "");
        meta.addProperty("truncationReason", snapshot != null && snapshot.truncationReason != null ? snapshot.truncationReason : "");
        meta.addProperty("rawBodyTruncated", snapshot != null && snapshot.rawBodyTruncated);
        meta.addProperty("originalRawBodyLength", snapshot != null ? snapshot.originalRawBodyLength : 0);
        meta.addProperty("storedRawBodyLength", snapshot != null ? snapshot.storedRawBodyLength : 0);
        meta.addProperty("fullRawBodySha256", snapshot != null && snapshot.fullRawBodySha256 != null ? snapshot.fullRawBodySha256 : "");
        meta.addProperty("rawTruncationReason", snapshot != null && snapshot.rawTruncationReason != null ? snapshot.rawTruncationReason : "");
        meta.addProperty("parseWarning", snapshot != null && snapshot.parseWarning != null ? snapshot.parseWarning : "");
        meta.addProperty("bodyMode", snapshot != null && snapshot.bodyMode != null ? snapshot.bodyMode : "");
        meta.addProperty("buildMode", snapshot != null && snapshot.buildMode != null ? snapshot.buildMode.name() : "");
        meta.addProperty("method", snapshot != null && snapshot.method != null ? snapshot.method : "");
        meta.addProperty("urlTemplate", snapshot != null && snapshot.urlTemplate != null ? snapshot.urlTemplate : "");
        meta.addProperty("resolvedUrl", snapshot != null && snapshot.resolvedUrl != null ? snapshot.resolvedUrl : "");
        return meta;
    }

    private JsonObject responseMetadata(HistoryEntry entry) {
        JsonObject meta = new JsonObject();
        HistoryResponseSnapshot snapshot = entry != null ? entry.responseSnapshot : null;
        meta.addProperty("bodyTruncated", snapshot != null && snapshot.bodyTruncated);
        meta.addProperty("originalBodyLength", snapshot != null ? snapshot.originalBodyLength : 0);
        meta.addProperty("storedBodyLength", snapshot != null ? snapshot.storedBodyLength : 0);
        meta.addProperty("fullBodySha256", snapshot != null && snapshot.fullBodySha256 != null ? snapshot.fullBodySha256 : "");
        meta.addProperty("truncationReason", snapshot != null && snapshot.truncationReason != null ? snapshot.truncationReason : "");
        meta.addProperty("statusCode", snapshot != null ? snapshot.statusCode : 0);
        meta.addProperty("reasonPhrase", snapshot != null && snapshot.reasonPhrase != null ? snapshot.reasonPhrase : "");
        meta.addProperty("mimeType", snapshot != null && snapshot.mimeType != null ? snapshot.mimeType : "");
        return meta;
    }

    private String analystComment(HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (entry.pinned) {
            sb.append("Pinned: Yes");
        }
        if (entry.tags != null && !entry.tags.isEmpty()) {
            appendCommentField(sb, "Tags", String.join(", ", entry.tags));
        }
        if (entry.analystNotes != null && !entry.analystNotes.isBlank()) {
            appendCommentField(sb, "Notes", HistorySanitizer.safeMultiline(entry.analystNotes));
        }
        return sb.toString().trim();
    }

    private void appendCommentField(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value);
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
