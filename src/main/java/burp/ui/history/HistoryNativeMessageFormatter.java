package burp.ui.history;

import burp.history.HistoryEntry;
import burp.history.HistoryHeader;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class HistoryNativeMessageFormatter {
    private static final String CRLF = "\r\n";

    private HistoryNativeMessageFormatter() {
    }

    static String requestMessage(HistoryEntry entry) {
        HistoryRequestSnapshot request = entry != null ? entry.requestSnapshot : null;
        if (request == null) {
            return "";
        }
        if (request.hasRawRequestSent()) {
            return request.preferredRawRequestText();
        }

        StringBuilder sb = new StringBuilder();
        String method = request.method != null && !request.method.isBlank() ? request.method.trim().toUpperCase() : "GET";
        String target = request.urlTemplate != null ? request.urlTemplate : "";
        sb.append(method).append(' ').append(target).append(" HTTP/1.1").append(CRLF);

        for (HistoryHeader header : request.headersAsAuthored != null ? request.headersAsAuthored : List.<HistoryHeader>of()) {
            if (header == null || header.disabled || header.name == null || header.name.isBlank()) {
                continue;
            }
            sb.append(header.name.trim())
                    .append(": ")
                    .append(header.value != null ? header.value : "")
                    .append(CRLF);
        }

        sb.append(CRLF);
        String body = request.displayBodyText();
        if (body != null && !body.isBlank()) {
            sb.append(body);
        }
        return sb.toString();
    }

    static String responseMessage(HistoryEntry entry) {
        HistoryResponseSnapshot response = entry != null ? entry.responseSnapshot : null;
        if (response == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (response.statusCode > 0) {
            sb.append("HTTP/1.1 ").append(response.statusCode);
            if (response.reasonPhrase != null && !response.reasonPhrase.isBlank()) {
                sb.append(' ').append(response.reasonPhrase);
            }
            sb.append(CRLF);
        }

        for (HistoryHeader header : response.headers != null ? response.headers : List.<HistoryHeader>of()) {
            if (header == null || header.name == null || header.name.isBlank()) {
                continue;
            }
            sb.append(header.name.trim())
                    .append(": ")
                    .append(header.value != null ? header.value : "")
                    .append(CRLF);
        }

        sb.append(CRLF);
        if (response.body != null && response.body.length > 0) {
            sb.append(new String(response.body, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
