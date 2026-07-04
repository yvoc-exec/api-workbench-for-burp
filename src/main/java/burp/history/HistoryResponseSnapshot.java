package burp.history;

import burp.models.RunnerResult;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryResponseSnapshot {
    public int statusCode;
    public String reasonPhrase;
    public List<HistoryHeader> headers = new ArrayList<>();
    public byte[] body;
    public String mimeType;
    public boolean bodyTruncated;
    public long originalBodyLength;
    public long storedBodyLength;
    public String fullBodySha256;
    public String truncationReason = "";

    public static HistoryResponseSnapshot from(HttpResponse response) {
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        if (response == null) {
            return snapshot;
        }
        snapshot.statusCode = response.statusCode();
        snapshot.reasonPhrase = "";
        snapshot.body = response.body() != null ? response.body().getBytes() : null;
        if (response.headers() != null) {
            for (var header : response.headers()) {
                if (header == null) {
                    continue;
                }
                snapshot.headers.add(new HistoryHeader(header.name(), header.value(), false));
                if (header.name() != null && "content-type".equalsIgnoreCase(header.name())) {
                    snapshot.mimeType = header.value();
                }
            }
        }
        if ((snapshot.mimeType == null || snapshot.mimeType.isBlank()) && snapshot.body != null) {
            snapshot.mimeType = "text/plain";
        }
        snapshot.originalBodyLength = snapshot.body != null ? snapshot.body.length : 0L;
        snapshot.storedBodyLength = snapshot.originalBodyLength;
        snapshot.fullBodySha256 = snapshot.originalBodyLength > 0
                ? HistoryBodyTruncator.sha256Hex(snapshot.body)
                : "";
        snapshot.bodyTruncated = false;
        snapshot.truncationReason = "";
        return snapshot;
    }

    public static HistoryResponseSnapshot from(RunnerResult result) {
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        if (result == null) {
            return snapshot;
        }
        snapshot.statusCode = result.statusCode;
        snapshot.reasonPhrase = parseReasonPhrase(result.responseHeaders, result.statusCode);
        snapshot.body = result.responseBody != null ? result.responseBody.getBytes(StandardCharsets.UTF_8) : null;
        snapshot.headers = parseHeaders(result.responseHeaders);
        snapshot.mimeType = findContentType(snapshot.headers);
        if ((snapshot.mimeType == null || snapshot.mimeType.isBlank()) && snapshot.body != null) {
            snapshot.mimeType = "text/plain";
        }
        snapshot.originalBodyLength = snapshot.body != null ? snapshot.body.length : 0L;
        snapshot.storedBodyLength = snapshot.originalBodyLength;
        snapshot.fullBodySha256 = snapshot.originalBodyLength > 0
                ? HistoryBodyTruncator.sha256Hex(snapshot.body)
                : "";
        snapshot.bodyTruncated = false;
        snapshot.truncationReason = "";
        return snapshot;
    }

    public static HistoryResponseSnapshot copyOf(HistoryResponseSnapshot source) {
        if (source == null) {
            return null;
        }
        HistoryResponseSnapshot copy = new HistoryResponseSnapshot();
        copy.statusCode = source.statusCode;
        copy.reasonPhrase = source.reasonPhrase;
        copy.headers = new ArrayList<>();
        if (source.headers != null) {
            for (HistoryHeader header : source.headers) {
                HistoryHeader headerCopy = HistoryHeader.copyOf(header);
                if (headerCopy != null) {
                    copy.headers.add(headerCopy);
                }
            }
        }
        copy.body = source.body != null ? source.body.clone() : null;
        copy.mimeType = source.mimeType;
        copy.bodyTruncated = source.bodyTruncated;
        copy.originalBodyLength = source.originalBodyLength;
        copy.storedBodyLength = source.storedBodyLength;
        copy.fullBodySha256 = source.fullBodySha256;
        copy.truncationReason = source.truncationReason;
        return copy;
    }

    public boolean hasBody() {
        return body != null && body.length > 0;
    }

    public String bodyAsText() {
        return body != null ? new String(body, StandardCharsets.UTF_8) : "";
    }

    public String displayHeaderBlock() {
        StringBuilder sb = new StringBuilder();
        if (statusCode > 0) {
            sb.append("HTTP/1.1 ").append(statusCode);
            if (reasonPhrase != null && !reasonPhrase.isBlank()) {
                sb.append(' ').append(reasonPhrase);
            }
            sb.append('\n');
        }
        for (HistoryHeader header : headers != null ? headers : List.<HistoryHeader>of()) {
            if (header == null) {
                continue;
            }
            sb.append(header.name != null ? header.name : "")
                    .append(": ")
                    .append(header.value != null ? header.value : "")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Status Code: ").append(statusCode > 0 ? statusCode : "n/a").append('\n');
        sb.append("Reason Phrase: ").append(reasonPhrase != null ? reasonPhrase : "").append('\n');
        sb.append("Mime Type: ").append(mimeType != null ? mimeType : "").append('\n');
        sb.append("Size: ").append(body != null ? body.length : 0).append(" bytes").append('\n');
        if (!headers.isEmpty()) {
            sb.append('\n').append("Headers:").append('\n').append(displayHeaderBlock()).append('\n');
        }
        if (hasBody()) {
            sb.append('\n').append("Body:").append('\n').append(bodyAsText()).append('\n');
        }
        String evidence = truncationSummary();
        if (!evidence.isBlank()) {
            sb.append('\n').append(evidence).append('\n');
        }
        return sb.toString().trim();
    }

    public String truncationSummary() {
        if (!bodyTruncated) {
            if (originalBodyLength > 0 && fullBodySha256 != null && !fullBodySha256.isBlank()) {
                return "Body: stored " + storedBodyLength + " of " + originalBodyLength + " bytes; SHA-256=" + fullBodySha256;
            }
            return "";
        }
        return "Body truncated: stored " + storedBodyLength + " of " + originalBodyLength + " bytes; SHA-256=" + (fullBodySha256 != null ? fullBodySha256 : "");
    }

    private static List<HistoryHeader> parseHeaders(String responseHeaders) {
        List<HistoryHeader> headers = new ArrayList<>();
        if (responseHeaders == null || responseHeaders.isBlank()) {
            return headers;
        }
        String[] lines = responseHeaders.replace("\r", "").split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.add(new HistoryHeader(name, value, false));
        }
        return headers;
    }

    private static String findContentType(List<HistoryHeader> headers) {
        for (HistoryHeader header : headers) {
            if (header != null && header.name != null && "content-type".equalsIgnoreCase(header.name)) {
                return header.value;
            }
        }
        return null;
    }

    private static String parseReasonPhrase(String responseHeaders, int statusCode) {
        if (responseHeaders == null || responseHeaders.isBlank()) {
            return "";
        }
        String[] lines = responseHeaders.replace("\r", "").split("\n");
        if (lines.length == 0) {
            return "";
        }
        String statusLine = lines[0].trim();
        if (statusLine.isBlank()) {
            return "";
        }
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            return "";
        }
        String[] parts = statusLine.substring(firstSpace + 1).trim().split("\\s+", 2);
        if (parts.length == 0) {
            return "";
        }
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[1];
    }
}
