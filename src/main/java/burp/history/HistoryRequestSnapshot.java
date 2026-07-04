package burp.history;

import burp.models.ApiRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class HistoryRequestSnapshot {
    private static final Gson COPY_GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String method;
    public String urlTemplate;
    public List<HistoryHeader> headersAsAuthored = new ArrayList<>();
    public byte[] bodyAsAuthored;
    public String bodyMode;
    public String authType;
    public ApiRequest.BuildMode buildMode;
    public Map<String, String> requestVariablesAsAuthored = new LinkedHashMap<>();
    public ApiRequest authoredRequest;
    public byte[] rawRequestSent;
    public String rawRequestSentText;
    public String resolvedUrl;
    public Map<String, String> resolvedVariables = new LinkedHashMap<>();
    public boolean bodyTruncated;
    public long originalBodyLength;
    public long storedBodyLength;
    public String fullBodySha256;
    public String truncationReason = "";
    public boolean rawBodyTruncated;
    public long originalRawBodyLength;
    public long storedRawBodyLength;
    public String fullRawBodySha256;
    public String rawTruncationReason = "";
    public String parseWarning = "";

    public static HistoryRequestSnapshot from(ApiRequest request) {
        HistoryRequestSnapshot snapshot = new HistoryRequestSnapshot();
        if (request == null) {
            return snapshot;
        }
        snapshot.authoredRequest = copyRequest(request);
        snapshot.method = request.method;
        snapshot.urlTemplate = request.url;
        snapshot.bodyMode = request.body != null ? request.body.mode : null;
        snapshot.authType = request.auth != null && request.auth.type != null
                ? request.auth.type
                : request.authOverrideMode;
        snapshot.buildMode = request.resolveBuildMode();
        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null) {
                    continue;
                }
                snapshot.headersAsAuthored.add(new HistoryHeader(header.key, header.value, header.disabled));
            }
        }
        if (request.variables != null) {
            for (ApiRequest.Variable variable : request.variables) {
                if (variable == null || variable.key == null || variable.key.isBlank()) {
                    continue;
                }
                snapshot.requestVariablesAsAuthored.put(variable.key, variable.value);
            }
        }
        snapshot.bodyAsAuthored = serializeBodyText(request).getBytes(StandardCharsets.UTF_8);
        snapshot.originalBodyLength = snapshot.bodyAsAuthored.length;
        snapshot.storedBodyLength = snapshot.bodyAsAuthored.length;
        snapshot.fullBodySha256 = snapshot.bodyAsAuthored.length > 0
                ? HistoryBodyTruncator.sha256Hex(snapshot.bodyAsAuthored)
                : "";
        snapshot.bodyTruncated = false;
        snapshot.truncationReason = "";
        snapshot.rawBodyTruncated = false;
        snapshot.originalRawBodyLength = 0L;
        snapshot.storedRawBodyLength = 0L;
        snapshot.fullRawBodySha256 = "";
        snapshot.rawTruncationReason = "";
        snapshot.parseWarning = "";
        return snapshot;
    }

    public static HistoryRequestSnapshot copyOf(HistoryRequestSnapshot source) {
        if (source == null) {
            return null;
        }
        HistoryRequestSnapshot copy = new HistoryRequestSnapshot();
        copy.method = source.method;
        copy.urlTemplate = source.urlTemplate;
        copy.headersAsAuthored = new ArrayList<>();
        if (source.headersAsAuthored != null) {
            for (HistoryHeader header : source.headersAsAuthored) {
                HistoryHeader headerCopy = HistoryHeader.copyOf(header);
                if (headerCopy != null) {
                    copy.headersAsAuthored.add(headerCopy);
                }
            }
        }
        copy.bodyAsAuthored = source.bodyAsAuthored != null ? source.bodyAsAuthored.clone() : null;
        copy.bodyMode = source.bodyMode;
        copy.authType = source.authType;
        copy.buildMode = source.buildMode;
        copy.requestVariablesAsAuthored = source.requestVariablesAsAuthored != null
                ? new LinkedHashMap<>(source.requestVariablesAsAuthored)
                : new LinkedHashMap<>();
        copy.authoredRequest = copyRequest(source.authoredRequest);
        copy.rawRequestSent = source.rawRequestSent != null ? source.rawRequestSent.clone() : null;
        copy.rawRequestSentText = source.rawRequestSentText;
        copy.resolvedUrl = source.resolvedUrl;
        copy.resolvedVariables = source.resolvedVariables != null ? new LinkedHashMap<>(source.resolvedVariables) : new LinkedHashMap<>();
        copy.bodyTruncated = source.bodyTruncated;
        copy.originalBodyLength = source.originalBodyLength;
        copy.storedBodyLength = source.storedBodyLength;
        copy.fullBodySha256 = source.fullBodySha256;
        copy.truncationReason = source.truncationReason;
        copy.rawBodyTruncated = source.rawBodyTruncated;
        copy.originalRawBodyLength = source.originalRawBodyLength;
        copy.storedRawBodyLength = source.storedRawBodyLength;
        copy.fullRawBodySha256 = source.fullRawBodySha256;
        copy.rawTruncationReason = source.rawTruncationReason;
        copy.parseWarning = source.parseWarning;
        return copy;
    }

    public String preferredRawRequestText() {
        if (rawRequestSentText != null && !rawRequestSentText.isBlank()) {
            return rawRequestSentText;
        }
        if (rawRequestSent != null && rawRequestSent.length > 0) {
            return new String(rawRequestSent, StandardCharsets.UTF_8);
        }
        return "";
    }

    public boolean hasRawRequestSent() {
        return (rawRequestSentText != null && !rawRequestSentText.isBlank()) || (rawRequestSent != null && rawRequestSent.length > 0);
    }

    public ApiRequest toApiRequest() {
        if (authoredRequest != null) {
            return copyRequest(authoredRequest);
        }
        ApiRequest request = new ApiRequest();
        request.method = method;
        request.url = urlTemplate;
        if (headersAsAuthored != null) {
            request.headers = new ArrayList<>();
            for (HistoryHeader header : headersAsAuthored) {
                if (header != null) {
                    request.headers.add(new ApiRequest.Header(header.name, header.value, header.disabled));
                }
            }
        }
        if (bodyMode != null || bodyAsAuthored != null) {
            request.body = new ApiRequest.Body();
            request.body.mode = bodyMode;
            request.body.raw = bodyAsAuthored != null ? new String(bodyAsAuthored, StandardCharsets.UTF_8) : null;
        }
        if (authType != null && !authType.isBlank()) {
            request.auth = new ApiRequest.Auth();
            request.auth.type = authType;
        }
        if (requestVariablesAsAuthored != null) {
            request.variables = new ArrayList<>();
            for (Map.Entry<String, String> entry : requestVariablesAsAuthored.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                ApiRequest.Variable variable = new ApiRequest.Variable();
                variable.key = entry.getKey();
                variable.value = entry.getValue();
                request.variables.add(variable);
            }
        }
        request.suppressedAutoHeaders = new LinkedHashSet<>();
        request.buildMode = buildMode != null ? buildMode : ApiRequest.BuildMode.MANUAL_PRESERVE;
        request.editorMaterialized = true;
        return request;
    }

    public String displayBodyText() {
        if (bodyAsAuthored != null) {
            return new String(bodyAsAuthored, StandardCharsets.UTF_8);
        }
        if (authoredRequest != null) {
            return serializeBodyText(authoredRequest);
        }
        return "";
    }

    public String truncationSummary() {
        StringBuilder sb = new StringBuilder();
        if (bodyTruncated) {
            appendEvidenceLine(sb, "Request body truncated", storedBodyLength, originalBodyLength, fullBodySha256);
        } else if (originalBodyLength > 0 && fullBodySha256 != null && !fullBodySha256.isBlank()) {
            appendEvidenceLine(sb, "Request body", storedBodyLength > 0 ? storedBodyLength : originalBodyLength, originalBodyLength, fullBodySha256);
        }
        if (rawBodyTruncated) {
            appendEvidenceLine(sb, "Raw request body truncated", storedRawBodyLength, originalRawBodyLength, fullRawBodySha256);
        } else if (originalRawBodyLength > 0 && fullRawBodySha256 != null && !fullRawBodySha256.isBlank()) {
            appendEvidenceLine(sb, "Raw request body", storedRawBodyLength > 0 ? storedRawBodyLength : originalRawBodyLength, originalRawBodyLength, fullRawBodySha256);
        }
        if (parseWarning != null && !parseWarning.isBlank()) {
            appendLine(sb, "Parse warning: " + parseWarning);
        }
        return sb.toString().trim();
    }

    public String toCurlCommand() {
        ApiRequest request = toApiRequest();
        StringBuilder out = new StringBuilder();
        String methodValue = request.method != null && !request.method.isBlank() ? request.method.toUpperCase(Locale.ROOT) : "GET";
        out.append("curl -X ").append(methodValue);
        out.append(" '").append(escapeSingleQuotes(request.url != null ? request.url : "")).append("'");
        if (request.headers != null) {
            for (ApiRequest.Header header : request.headers) {
                if (header == null || header.key == null || header.key.isBlank() || header.disabled) {
                    continue;
                }
                out.append(" -H '")
                        .append(escapeSingleQuotes(header.key + ": " + (header.value != null ? header.value : "")))
                        .append("'");
            }
        }
        String bodyText = displayBodyText();
        if (bodyText != null && !bodyText.isBlank()) {
            out.append(" --data-raw '").append(escapeSingleQuotes(bodyText)).append("'");
        }
        return out.toString();
    }

    public long approximateSizeBytes() {
        long size = 0L;
        if (method != null) {
            size += method.getBytes(StandardCharsets.UTF_8).length;
        }
        if (urlTemplate != null) {
            size += urlTemplate.getBytes(StandardCharsets.UTF_8).length;
        }
        for (HistoryHeader header : headersAsAuthored != null ? headersAsAuthored : List.<HistoryHeader>of()) {
            if (header == null) {
                continue;
            }
            if (header.name != null) {
                size += header.name.getBytes(StandardCharsets.UTF_8).length;
            }
            if (header.value != null) {
                size += header.value.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (bodyAsAuthored != null) {
            size += bodyAsAuthored.length;
        }
        if (rawRequestSent != null) {
            size += rawRequestSent.length;
        }
        if (rawRequestSentText != null) {
            size += rawRequestSentText.getBytes(StandardCharsets.UTF_8).length;
        }
        if (resolvedUrl != null) {
            size += resolvedUrl.getBytes(StandardCharsets.UTF_8).length;
        }
        if (bodyMode != null) {
            size += bodyMode.getBytes(StandardCharsets.UTF_8).length;
        }
        if (authType != null) {
            size += authType.getBytes(StandardCharsets.UTF_8).length;
        }
        if (buildMode != null) {
            size += buildMode.name().getBytes(StandardCharsets.UTF_8).length;
        }
        if (fullBodySha256 != null) {
            size += fullBodySha256.getBytes(StandardCharsets.UTF_8).length;
        }
        if (truncationReason != null) {
            size += truncationReason.getBytes(StandardCharsets.UTF_8).length;
        }
        if (fullRawBodySha256 != null) {
            size += fullRawBodySha256.getBytes(StandardCharsets.UTF_8).length;
        }
        if (rawTruncationReason != null) {
            size += rawTruncationReason.getBytes(StandardCharsets.UTF_8).length;
        }
        if (parseWarning != null) {
            size += parseWarning.getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    public String displayHeaderBlock() {
        StringBuilder sb = new StringBuilder();
        for (HistoryHeader header : headersAsAuthored != null ? headersAsAuthored : List.<HistoryHeader>of()) {
            if (header == null) {
                continue;
            }
            sb.append(header.disabled ? "[disabled] " : "")
                    .append(header.name != null ? header.name : "")
                    .append(": ")
                    .append(header.value != null ? header.value : "")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Method: ").append(method != null ? method : "GET").append('\n');
        sb.append("URL Template: ").append(urlTemplate != null ? urlTemplate : "").append('\n');
        sb.append("Auth Type: ").append(authType != null ? authType : "none").append('\n');
        sb.append("Body Mode: ").append(bodyMode != null ? bodyMode : "none").append('\n');
        if (!headersAsAuthored.isEmpty()) {
            sb.append('\n').append("Headers as Authored:").append('\n').append(displayHeaderBlock()).append('\n');
        }
        String bodyText = displayBodyText();
        if (!bodyText.isBlank()) {
            sb.append('\n').append("Body as Authored:").append('\n').append(bodyText).append('\n');
        }
        String evidence = truncationSummary();
        if (!evidence.isBlank()) {
            sb.append('\n').append(evidence).append('\n');
        }
        return sb.toString().trim();
    }

    private static void appendEvidenceLine(StringBuilder sb, String label, long stored, long original, String hash) {
        appendLine(sb, label + ": stored " + stored + " of " + original + " bytes; SHA-256=" + (hash != null ? hash : ""));
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static String serializeBodyText(ApiRequest request) {
        if (request == null || request.body == null || request.body.mode == null || "none".equalsIgnoreCase(request.body.mode)) {
            return "";
        }
        String mode = request.body.mode.toLowerCase(Locale.ROOT);
        if ("raw".equals(mode)) {
            return request.body.raw != null ? request.body.raw : "";
        }
        if ("graphql".equals(mode)) {
            String query = request.body.graphql != null ? request.body.graphql.query : null;
            String variables = request.body.graphql != null ? request.body.graphql.variables : null;
            return "{\"query\":" + jsonString(query) + ",\"variables\":" + jsonString(variables) + "}";
        }
        StringBuilder sb = new StringBuilder();
        List<ApiRequest.Body.FormField> fields = "urlencoded".equals(mode)
                ? request.body.urlencoded
                : request.body.formdata;
        if (fields != null) {
            for (ApiRequest.Body.FormField field : fields) {
                if (field == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(field.disabled ? "# " : "");
                sb.append(field.key != null ? field.key : "");
                sb.append('=');
                if (field.fileUpload || "file".equalsIgnoreCase(field.type)) {
                    sb.append(field.filePath != null ? field.filePath : "");
                } else {
                    sb.append(field.value != null ? field.value : "");
                }
            }
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        return new com.google.gson.JsonPrimitive(value != null ? value : "").toString();
    }

    private static String escapeSingleQuotes(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("'", "'\"'\"'");
    }

    private static ApiRequest copyRequest(ApiRequest request) {
        if (request == null) {
            return null;
        }
        return COPY_GSON.fromJson(COPY_GSON.toJson(request), ApiRequest.class);
    }
}
