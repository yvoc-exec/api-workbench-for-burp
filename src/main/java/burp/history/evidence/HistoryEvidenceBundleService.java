package burp.history.evidence;

import burp.history.HistoryBodyTruncator;
import burp.history.HistoryEntry;
import burp.history.HistoryHeader;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import burp.models.ApiRequest;
import burp.models.RedirectHop;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HistoryEvidenceBundleService {
    public static final String FORMAT = "api-workbench-evidence-bundle";
    public static final int VERSION = 1;

    private final Gson gson;
    private final HistoryEvidenceRedactor redactor;

    public HistoryEvidenceBundleService() {
        this(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create(), new HistoryEvidenceRedactor());
    }

    HistoryEvidenceBundleService(Gson gson, HistoryEvidenceRedactor redactor) {
        this.gson = gson != null ? gson : new GsonBuilder().setPrettyPrinting().create();
        this.redactor = redactor != null ? redactor : new HistoryEvidenceRedactor();
    }

    public ExportResult export(List<HistoryEntry> sourceEntries,
                               HistoryEvidenceBundleOptions options) throws IOException {
        if (options == null || options.destination == null) {
            throw new IllegalArgumentException("Evidence bundle destination is required.");
        }
        Path destination = options.destination.toAbsolutePath().normalize();
        if (Files.exists(destination) && Files.isDirectory(destination)) {
            throw new IOException("Evidence bundle destination is a directory.");
        }
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Evidence bundle destination has no parent directory.");
        }
        Files.createDirectories(parent);

        List<HistoryEntry> entries = detachedAndSorted(sourceEntries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("At least one History entry is required.");
        }

        Instant exportedAt = options.clock.instant();
        long zipTime = exportedAt.toEpochMilli();
        LinkedHashMap<String, byte[]> payloads = new LinkedHashMap<>();
        List<Map<String, Object>> manifestEntries = new ArrayList<>();
        List<String> summaryRows = new ArrayList<>();
        summaryRows.add("id,timestamp,source,collection,request,method,url,status,pinned,tags,requestTruncated,responseTruncated,directory");

        Set<String> usedDirectories = new LinkedHashSet<>();
        int index = 0;
        for (HistoryEntry entry : entries) {
            index++;
            String directoryName = uniqueDirectoryName(entry != null ? entry.id : null, index, usedDirectories);
            String directory = "entries/" + directoryName + "/";
            byte[] requestBytes = requestBytes(entry);
            String requestRepresentation = hasRawRequest(entry) ? exactRequestRepresentation(entry) : "RECONSTRUCTED";
            byte[] responseBytes = responseBytes(entry);
            String responseRepresentation = responseBytes.length > 0 ? "RECONSTRUCTED" : "RECONSTRUCTED";
            if (options.redactCommonSecrets) {
                requestBytes = redactor.redactRequest(requestBytes);
                responseBytes = redactor.redactResponse(responseBytes);
            }

            LinkedHashMap<String, Object> metadata = metadata(entry, requestRepresentation, responseRepresentation);
            if (options.redactCommonSecrets) {
                redactMetadataValues(metadata);
            }
            byte[] metadataBytes = gson.toJson(metadata).getBytes(StandardCharsets.UTF_8);
            byte[] notesBytes = null;
            String notes = entry != null && entry.analystNotes != null ? entry.analystNotes : "";
            if (!notes.isBlank()) {
                if (options.redactCommonSecrets) {
                    notes = redactor.redactMetadataText(notes);
                }
                notesBytes = notes.getBytes(StandardCharsets.UTF_8);
            }

            String requestPath = directory + "request.txt";
            String responsePath = directory + "response.txt";
            String metadataPath = directory + "metadata.json";
            payloads.put(requestPath, requestBytes);
            payloads.put(responsePath, responseBytes);
            payloads.put(metadataPath, metadataBytes);
            String notesPath = null;
            if (notesBytes != null) {
                notesPath = directory + "notes.txt";
                payloads.put(notesPath, notesBytes);
            }

            LinkedHashMap<String, Object> manifestEntry = new LinkedHashMap<>();
            manifestEntry.put("id", safe(entry != null ? entry.id : null));
            manifestEntry.put("timestamp", entry != null && entry.timestamp != null ? entry.timestamp.toString() : "");
            manifestEntry.put("source", entry != null && entry.source != null ? entry.source.name() : "");
            manifestEntry.put("collectionId", safe(entry != null ? entry.collectionId : null));
            manifestEntry.put("collectionName", safe(entry != null ? entry.collectionName : null));
            manifestEntry.put("requestId", safe(entry != null ? entry.requestId : null));
            manifestEntry.put("requestName", safe(entry != null ? entry.requestName : null));
            manifestEntry.put("method", requestMethod(entry));
            manifestEntry.put("url", options.redactCommonSecrets
                    ? redactor.redactMetadataText(requestUrl(entry))
                    : requestUrl(entry));
            manifestEntry.put("pinned", entry != null && entry.pinned);
            manifestEntry.put("tags", entry != null && entry.tags != null ? new ArrayList<>(entry.tags) : List.of());
            manifestEntry.put("requestRepresentation", requestRepresentation);
            manifestEntry.put("responseRepresentation", responseRepresentation);
            manifestEntry.put("requestTruncation", requestTruncation(entry));
            manifestEntry.put("responseTruncation", responseTruncation(entry));
            manifestEntry.put("redirectTruncation", redirectTruncation(entry));
            manifestEntry.put("directory", directory);
            LinkedHashMap<String, Object> files = new LinkedHashMap<>();
            files.put("request", fileDescriptor(requestPath, requestBytes));
            files.put("response", fileDescriptor(responsePath, responseBytes));
            files.put("metadata", fileDescriptor(metadataPath, metadataBytes));
            if (notesPath != null) {
                files.put("notes", fileDescriptor(notesPath, notesBytes));
            }
            manifestEntry.put("files", files);
            manifestEntries.add(manifestEntry);

            summaryRows.add(csvRow(List.of(
                    safe(entry != null ? entry.id : null),
                    entry != null && entry.timestamp != null ? entry.timestamp.toString() : "",
                    entry != null && entry.source != null ? entry.source.name() : "",
                    safe(entry != null ? entry.collectionName : null),
                    safe(entry != null ? entry.requestName : null),
                    requestMethod(entry),
                    options.redactCommonSecrets ? redactor.redactMetadataText(requestUrl(entry)) : requestUrl(entry),
                    entry != null ? String.valueOf(entry.statusCode) : "",
                    entry != null && entry.pinned ? "true" : "false",
                    entry != null && entry.tags != null ? String.join("|", entry.tags) : "",
                    isRequestTruncated(entry) ? "true" : "false",
                    isResponseTruncated(entry) ? "true" : "false",
                    directoryName
            )));
        }

        byte[] summaryBytes = (String.join("\r\n", summaryRows) + "\r\n").getBytes(StandardCharsets.UTF_8);
        LinkedHashMap<String, byte[]> orderedPayloads = new LinkedHashMap<>();
        orderedPayloads.put("summary.csv", summaryBytes);
        orderedPayloads.putAll(payloads);

        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("version", VERSION);
        manifest.put("exportTimestamp", exportedAt.toString());
        manifest.put("extensionVersion", options.extensionVersion);
        manifest.put("redactionApplied", options.redactCommonSecrets);
        manifest.put("entryCount", entries.size());
        manifest.put("summary", fileDescriptor("summary.csv", summaryBytes));
        manifest.put("entries", manifestEntries);
        byte[] manifestBytes = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);

        Path temporary = Files.createTempFile(parent, ".api-workbench-evidence-", ".tmp");
        boolean moved = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                writeEntry(zip, "manifest.json", manifestBytes, zipTime);
                for (Map.Entry<String, byte[]> payload : orderedPayloads.entrySet()) {
                    writeEntry(zip, payload.getKey(), payload.getValue(), zipTime);
                }
            }
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return new ExportResult(destination, entries.size(), options.redactCommonSecrets,
                    Files.size(destination), HistoryBodyTruncator.sha256Hex(Files.readAllBytes(destination)));
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private List<HistoryEntry> detachedAndSorted(List<HistoryEntry> source) {
        List<HistoryEntry> entries = new ArrayList<>();
        if (source != null) {
            for (HistoryEntry entry : source) {
                HistoryEntry copy = HistoryEntry.copyOf(entry);
                if (copy != null) {
                    entries.add(copy);
                }
            }
        }
        entries.sort(Comparator
                .comparing((HistoryEntry entry) -> entry.timestamp != null ? entry.timestamp : Instant.EPOCH)
                .thenComparing(entry -> safe(entry.id)));
        return entries;
    }

    private byte[] requestBytes(HistoryEntry entry) {
        HistoryRequestSnapshot snapshot = entry != null ? entry.requestSnapshot : null;
        if (snapshot != null && snapshot.rawRequestSent != null && snapshot.rawRequestSent.length > 0) {
            return snapshot.rawRequestSent.clone();
        }
        if (snapshot == null) {
            return new byte[0];
        }
        String method = snapshot.method != null && !snapshot.method.isBlank() ? snapshot.method : "GET";
        String target = requestTarget(snapshot.urlTemplate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((method + " " + target + " HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (snapshot.headersAsAuthored != null) {
            for (HistoryHeader header : snapshot.headersAsAuthored) {
                if (header == null || header.disabled) {
                    continue;
                }
                out.writeBytes((safe(header.name) + ": " + safe(header.value) + "\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (snapshot.bodyAsAuthored != null) {
            out.writeBytes(snapshot.bodyAsAuthored);
        }
        return out.toByteArray();
    }

    private byte[] responseBytes(HistoryEntry entry) {
        HistoryResponseSnapshot snapshot = entry != null ? entry.responseSnapshot : null;
        if (snapshot == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = snapshot.statusCode > 0 ? snapshot.statusCode : entry.statusCode;
        out.writeBytes(("HTTP/1.1 " + (status > 0 ? status : 0)
                + (snapshot.reasonPhrase != null && !snapshot.reasonPhrase.isBlank() ? " " + snapshot.reasonPhrase : "")
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (snapshot.headers != null) {
            for (HistoryHeader header : snapshot.headers) {
                if (header == null || header.disabled) {
                    continue;
                }
                out.writeBytes((safe(header.name) + ": " + safe(header.value) + "\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (snapshot.body != null) {
            out.writeBytes(snapshot.body);
        }
        return out.toByteArray();
    }

    private LinkedHashMap<String, Object> metadata(HistoryEntry entry,
                                                    String requestRepresentation,
                                                    String responseRepresentation) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", safe(entry != null ? entry.id : null));
        metadata.put("source", entry != null && entry.source != null ? entry.source.name() : "");
        metadata.put("timestamp", entry != null && entry.timestamp != null ? entry.timestamp.toString() : "");
        metadata.put("collectionId", safe(entry != null ? entry.collectionId : null));
        metadata.put("collectionName", safe(entry != null ? entry.collectionName : null));
        metadata.put("folderPath", safe(entry != null ? entry.folderPath : null));
        metadata.put("requestId", safe(entry != null ? entry.requestId : null));
        metadata.put("requestName", safe(entry != null ? entry.requestName : null));
        metadata.put("method", requestMethod(entry));
        metadata.put("url", requestUrl(entry));
        metadata.put("status", entry != null ? entry.statusCode : -1);
        metadata.put("result", entry != null && entry.result != null ? entry.result.name() : "");
        metadata.put("pinned", entry != null && entry.pinned);
        metadata.put("tags", entry != null && entry.tags != null ? new ArrayList<>(entry.tags) : List.of());
        metadata.put("requestRepresentation", requestRepresentation);
        metadata.put("responseRepresentation", responseRepresentation);
        metadata.put("requestTruncation", requestTruncation(entry));
        metadata.put("responseTruncation", responseTruncation(entry));
        metadata.put("redirectTruncation", redirectTruncation(entry));
        metadata.put("parseWarning", entry != null && entry.requestSnapshot != null
                ? safe(entry.requestSnapshot.parseWarning) : "");
        metadata.put("metadataSummary", entry != null ? safe(entry.metadataSummaryText) : "");
        return metadata;
    }

    private void redactMetadataValues(Map<String, Object> metadata) {
        for (Map.Entry<String, Object> item : metadata.entrySet()) {
            if (item.getValue() instanceof String text) {
                item.setValue(redactor.redactMetadataText(text));
            }
        }
    }

    private Map<String, Object> requestTruncation(HistoryEntry entry) {
        HistoryRequestSnapshot snapshot = entry != null ? entry.requestSnapshot : null;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (snapshot == null) {
            return out;
        }
        out.put("bodyTruncated", snapshot.bodyTruncated);
        out.put("originalBodyLength", snapshot.originalBodyLength);
        out.put("storedBodyLength", snapshot.storedBodyLength);
        out.put("fullBodySha256", safe(snapshot.fullBodySha256));
        out.put("reason", safe(snapshot.truncationReason));
        out.put("rawBodyTruncated", snapshot.rawBodyTruncated);
        out.put("originalRawBodyLength", snapshot.originalRawBodyLength);
        out.put("storedRawBodyLength", snapshot.storedRawBodyLength);
        out.put("fullRawBodySha256", safe(snapshot.fullRawBodySha256));
        out.put("rawReason", safe(snapshot.rawTruncationReason));
        return out;
    }

    private Map<String, Object> responseTruncation(HistoryEntry entry) {
        HistoryResponseSnapshot snapshot = entry != null ? entry.responseSnapshot : null;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (snapshot == null) {
            return out;
        }
        out.put("bodyTruncated", snapshot.bodyTruncated);
        out.put("originalBodyLength", snapshot.originalBodyLength);
        out.put("storedBodyLength", snapshot.storedBodyLength);
        out.put("fullBodySha256", safe(snapshot.fullBodySha256));
        out.put("reason", safe(snapshot.truncationReason));
        return out;
    }

    private List<Map<String, Object>> redirectTruncation(HistoryEntry entry) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (entry == null || entry.redirectHops == null) {
            return out;
        }
        int index = 0;
        for (RedirectHop hop : entry.redirectHops) {
            index++;
            if (hop == null || (!hop.rawRequestBodyTruncated && !hop.responseBodyTruncated)) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("hop", hop.hopNumber > 0 ? hop.hopNumber : index);
            item.put("requestTruncated", hop.rawRequestBodyTruncated);
            item.put("requestOriginalLength", hop.originalRawRequestBodyLength);
            item.put("requestStoredLength", hop.storedRawRequestBodyLength);
            item.put("requestSha256", safe(hop.fullRawRequestBodySha256));
            item.put("requestReason", safe(hop.rawRequestTruncationReason));
            item.put("responseTruncated", hop.responseBodyTruncated);
            item.put("responseOriginalLength", hop.originalResponseBodyLength);
            item.put("responseStoredLength", hop.storedResponseBodyLength);
            item.put("responseSha256", safe(hop.fullResponseBodySha256));
            item.put("responseReason", safe(hop.responseTruncationReason));
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> fileDescriptor(String path, byte[] bytes) {
        LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("path", path);
        descriptor.put("size", bytes != null ? bytes.length : 0);
        descriptor.put("sha256", HistoryBodyTruncator.sha256Hex(bytes != null ? bytes : new byte[0]));
        return descriptor;
    }

    private String uniqueDirectoryName(String rawId, int index, Set<String> used) {
        String safe = rawId != null ? rawId.replaceAll("[^A-Za-z0-9._-]", "_") : "";
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            safe = "entry-" + index;
        }
        String candidate = safe;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = safe + "-" + suffix++;
        }
        return candidate;
    }

    private void writeEntry(ZipOutputStream zip, String path, byte[] bytes, long timestamp) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("../")) {
            throw new IOException("Unsafe evidence ZIP entry path.");
        }
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(timestamp);
        zip.putNextEntry(entry);
        zip.write(bytes != null ? bytes : new byte[0]);
        zip.closeEntry();
    }

    private String exactRequestRepresentation(HistoryEntry entry) {
        ApiRequest authored = entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.authoredRequest : null;
        return authored != null && authored.exactHttpRequest != null ? "EXACT_RAW" : "STORED_RAW";
    }

    private boolean hasRawRequest(HistoryEntry entry) {
        return entry != null && entry.requestSnapshot != null
                && entry.requestSnapshot.rawRequestSent != null
                && entry.requestSnapshot.rawRequestSent.length > 0;
    }

    private String requestMethod(HistoryEntry entry) {
        return entry != null && entry.requestSnapshot != null ? safe(entry.requestSnapshot.method) : "";
    }

    private String requestUrl(HistoryEntry entry) {
        if (entry == null || entry.requestSnapshot == null) {
            return "";
        }
        return entry.requestSnapshot.resolvedUrl != null && !entry.requestSnapshot.resolvedUrl.isBlank()
                ? entry.requestSnapshot.resolvedUrl
                : safe(entry.requestSnapshot.urlTemplate);
    }

    private String requestTarget(String url) {
        if (url == null || url.isBlank()) {
            return "/";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        } catch (RuntimeException ignored) {
            return url.startsWith("/") ? url : "/";
        }
    }

    private boolean isRequestTruncated(HistoryEntry entry) {
        return entry != null && entry.requestSnapshot != null
                && (entry.requestSnapshot.bodyTruncated || entry.requestSnapshot.rawBodyTruncated);
    }

    private boolean isResponseTruncated(HistoryEntry entry) {
        return entry != null && entry.responseSnapshot != null && entry.responseSnapshot.bodyTruncated;
    }

    private String csvRow(List<String> values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            String safeValue = protectCsvFormula(value != null ? value : "");
            escaped.add("\"" + safeValue.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private String protectCsvFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value != null ? value : "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + value : value;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public record ExportResult(Path destination,
                               int entryCount,
                               boolean redactionApplied,
                               long sizeBytes,
                               String sha256) {
    }
}
