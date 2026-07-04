package burp.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

public class HistoryCsvExportService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());

    public String export(Collection<HistoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Time,Source,Attempt,Collection,Folder,Request,Method,URL Template,Status,Duration,Size,Environment,Result,Pinned,Tags,Notes,Request Body Truncated,Request Original Body Bytes,Request Stored Body Bytes,Request Body SHA-256,Response Body Truncated,Response Original Body Bytes,Response Stored Body Bytes,Response Body SHA-256,Error\n");
        for (HistoryEntry entry : entries != null ? entries : List.<HistoryEntry>of()) {
            sb.append(row(entry)).append('\n');
        }
        return sb.toString();
    }

    public void write(Collection<HistoryEntry> entries, Path output) throws IOException {
        if (output == null) {
            throw new IOException("Output path is required");
        }
        Files.createDirectories(output.getParent() != null ? output.getParent() : output.toAbsolutePath().getParent());
        Files.writeString(output, export(entries), StandardCharsets.UTF_8);
    }

    private String row(HistoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(HistorySanitizer.csvCell(entry != null && entry.timestamp != null ? TIME.format(entry.timestamp) : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.source != null ? entry.source.displayName() : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.attemptDisplay() : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.collectionName : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.folderPath : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.requestName : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.method : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.urlTemplate : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? String.valueOf(entry.statusCode > 0 ? entry.statusCode : "") : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? String.valueOf(entry.durationMillis) : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.historySizeLabel() : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.environmentName : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.resultDisplayName() : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.pinned ? "Yes" : "No"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.tags != null ? String.join(", ", entry.tags) : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.analystNotes : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null && entry.requestSnapshot.bodyTruncated ? "Yes" : "No"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null ? String.valueOf(entry.requestSnapshot.originalBodyLength) : "0"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null ? String.valueOf(entry.requestSnapshot.storedBodyLength) : "0"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.requestSnapshot != null ? entry.requestSnapshot.fullBodySha256 : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.responseSnapshot != null && entry.responseSnapshot.bodyTruncated ? "Yes" : "No"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.responseSnapshot != null ? String.valueOf(entry.responseSnapshot.originalBodyLength) : "0"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.responseSnapshot != null ? String.valueOf(entry.responseSnapshot.storedBodyLength) : "0"));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null && entry.responseSnapshot != null ? entry.responseSnapshot.fullBodySha256 : ""));
        sb.append(',').append(HistorySanitizer.csvCell(entry != null ? entry.errorMessage : ""));
        return sb.toString();
    }
}
