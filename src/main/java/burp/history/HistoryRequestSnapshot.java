package burp.history;

import burp.models.ApiRequest;
import burp.payload.ManagedPayloadRef;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HistoryRequestSnapshot {
    public String method;
    public String urlTemplate;
    public List<HistoryHeader> headersAsAuthored = new ArrayList<>();
    public byte[] bodyAsAuthored;
    public String bodyMode;
    public String authType;
    public ApiRequest.BuildMode buildMode;
    public Map<String, String> requestVariablesAsAuthored = new LinkedHashMap<>();
    public ApiRequest authoredRequest;
    public ManagedPayloadRef authoredExactPayloadRef;
    public transient boolean authoredExactPayloadUnavailable;
    public boolean rawRequestSentUsesAuthoredExactPayload;
    /** Legacy workspace compatibility and bounded distinct evidence only. */
    public byte[] authoredExactRequestBytes;
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
        return captureFrom(request, false);
    }

    public static HistoryRequestSnapshot fromWithoutExactTransport(ApiRequest request) {
        return captureFrom(request, false);
    }

    /** Borrows immutable exact bytes until bounded History capture takes ownership. */
    public static HistoryRequestSnapshot fromBorrowingExactTransport(ApiRequest request) {
        return captureFrom(request, true);
    }

    private static HistoryRequestSnapshot captureFrom(ApiRequest request, boolean borrowExactTransport) {
        HistoryRequestSnapshot snapshot = new HistoryRequestSnapshot();
        if (request == null) {
            return snapshot;
        }
        snapshot.authoredRequest = copyRequestWithoutExactTransport(request);
        snapshot.authoredExactPayloadRef = request.exactHttpRequest != null
                && request.exactHttpRequest.payloadRef != null
                ? request.exactHttpRequest.payloadRef.copy() : null;
        snapshot.authoredExactRequestBytes = request.exactHttpRequest != null
                && request.exactHttpRequest.rawRequestBytes != null
                ? borrowExactTransport
                ? request.exactHttpRequest.rawRequestBytes
                : request.exactHttpRequest.rawRequestBytes.clone()
                : null;
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
        String authoredBodyText = serializeBodyText(request);
        snapshot.bodyAsAuthored = authoredBodyText.getBytes(StandardCharsets.UTF_8);
        if (request.hasDerivedExactTextBody()
                && snapshot.authoredRequest != null
                && snapshot.authoredRequest.body != null) {
            snapshot.authoredRequest.body.raw = authoredBodyText;
        }
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
        snapshot.canonicalizeExactTransportOwnership();
        return snapshot;
    }

    public static HistoryRequestSnapshot copyOf(HistoryRequestSnapshot source) {
        return copyCanonical(source, false);
    }

    static HistoryRequestSnapshot copyOfWithoutExactTransport(HistoryRequestSnapshot source) {
        return copyCanonical(source, false);
    }

    static HistoryRequestSnapshot copyForPersistenceSharingPayload(HistoryRequestSnapshot source) {
        return copyCanonical(source, true);
    }

    private static HistoryRequestSnapshot copyCanonical(HistoryRequestSnapshot source,
                                                        boolean sharePayload) {
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
        copy.bodyAsAuthored = source.bodyAsAuthored != null
                ? (sharePayload ? source.bodyAsAuthored : source.bodyAsAuthored.clone()) : null;
        copy.bodyMode = source.bodyMode;
        copy.authType = source.authType;
        copy.buildMode = source.buildMode;
        copy.requestVariablesAsAuthored = source.requestVariablesAsAuthored != null
                ? new LinkedHashMap<>(source.requestVariablesAsAuthored)
                : new LinkedHashMap<>();
        copy.authoredRequest = copyRequestWithoutExactTransport(source.authoredRequest);
        copy.authoredExactPayloadRef = source.authoredExactPayloadRef != null
                ? source.authoredExactPayloadRef.copy() : null;
        copy.authoredExactPayloadUnavailable = source.authoredExactPayloadUnavailable;
        copy.rawRequestSentUsesAuthoredExactPayload = source.rawRequestSentUsesAuthoredExactPayload;
        copy.rawRequestSent = source.rawRequestSent != null
                ? (sharePayload ? source.rawRequestSent : source.rawRequestSent.clone()) : null;
        copy.rawRequestSentText = source.rawRequestSentText;
        byte[] sourceAuthoredExact = source.authoredExactRequestBytes;
        if ((sourceAuthoredExact == null || sourceAuthoredExact.length == 0)
                && source.authoredRequest != null
                && source.authoredRequest.exactHttpRequest != null) {
            sourceAuthoredExact = source.authoredRequest.exactHttpRequest.rawRequestBytes;
        }
        if (sourceAuthoredExact != null && sourceAuthoredExact.length > 0) {
            copy.authoredExactRequestBytes = sourceAuthoredExact == source.rawRequestSent
                    && copy.rawRequestSent != null
                    ? copy.rawRequestSent
                    : sharePayload ? sourceAuthoredExact : sourceAuthoredExact.clone();
        }
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
        copy.canonicalizeExactTransportOwnership();
        return copy;
    }

    public String preferredRawRequestText() {
        if (rawRequestSent != null && rawRequestSent.length > 0) {
            return new String(rawRequestSent, StandardCharsets.UTF_8);
        }
        if (rawRequestSentText != null && !rawRequestSentText.isBlank()) {
            return rawRequestSentText;
        }
        return "";
    }

    /**
     * Retains exactly one raw request representation. Bytes win whenever they
     * exist; legacy text is promoted only when the repository's UTF-8 raw
     * message conversion round-trips every character without replacement.
     */
    public void canonicalizeRawEvidence() {
        if (rawRequestSent != null && rawRequestSent.length > 0) {
            rawRequestSentText = null;
            return;
        }
        rawRequestSent = null;
        if (rawRequestSentText == null || rawRequestSentText.isBlank()) {
            rawRequestSentText = null;
            return;
        }
        byte[] encoded = rawRequestSentText.getBytes(StandardCharsets.UTF_8);
        if (rawRequestSentText.equals(new String(encoded, StandardCharsets.UTF_8))) {
            rawRequestSent = encoded;
            rawRequestSentText = null;
        }
    }

    /**
     * Keeps exact authored transport separate from runtime evidence while
     * collapsing byte-identical representations onto one immutable owner.
     */
    public void canonicalizeExactTransportOwnership() {
        canonicalizeRawEvidence();
        if (authoredRequest != null && authoredRequest.exactHttpRequest != null) {
            if (authoredExactPayloadRef == null && authoredRequest.exactHttpRequest.payloadRef != null) {
                authoredExactPayloadRef = authoredRequest.exactHttpRequest.payloadRef.copy();
            }
            byte[] nested = authoredRequest.exactHttpRequest.rawRequestBytes;
            if ((authoredExactRequestBytes == null || authoredExactRequestBytes.length == 0)
                    && nested != null && nested.length > 0) {
                authoredExactRequestBytes = nested.clone();
            }
            authoredRequest.exactHttpRequest =
                    burp.models.ExactHttpRequestSnapshot.copyMetadataOnly(authoredRequest.exactHttpRequest);
            authoredRequest.exactHttpRequest.payloadRef = null;
        }
        if (authoredExactRequestBytes != null && authoredExactRequestBytes.length == 0) {
            authoredExactRequestBytes = null;
        }
        if (!rawBodyTruncated
                && authoredExactRequestBytes != null
                && rawRequestSent != null
                && authoredExactRequestBytes.length == rawRequestSent.length
                && (authoredExactRequestBytes == rawRequestSent
                || Arrays.equals(authoredExactRequestBytes, rawRequestSent))) {
            authoredExactRequestBytes = rawRequestSent;
        }
        if (rawRequestSentUsesAuthoredExactPayload && authoredExactPayloadRef == null) {
            rawRequestSentUsesAuthoredExactPayload = false;
        }
    }

    public void discardAuthoredExactTransport(String reason) {
        authoredExactPayloadRef = null;
        rawRequestSentUsesAuthoredExactPayload = false;
        authoredExactRequestBytes = null;
        if (authoredRequest != null) {
            if (authoredRequest.body != null) {
                authoredRequest.body.managedPayload = null;
            }
            if (authoredRequest.exactHttpRequest != null) {
                authoredRequest.exactHttpRequest.payloadRef = null;
                authoredRequest.exactHttpRequest.rawRequestBytes = null;
                authoredRequest.exactHttpRequest.pristine = false;
                authoredRequest.exactHttpRequest.invalidationReason = reason != null ? reason : "";
            }
        }
    }

    public boolean hasRawRequestSent() {
        return rawRequestSentUsesAuthoredExactPayload
                || (rawRequestSentText != null && !rawRequestSentText.isBlank())
                || (rawRequestSent != null && rawRequestSent.length > 0);
    }

    public ApiRequest toAuthoredApiRequest() {
        if (authoredRequest != null) {
            ApiRequest request = copyRequestWithoutExactTransport(authoredRequest);
            restoreAuthoredExactTransport(request);
            return request;
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
        restoreAuthoredExactTransport(request);
        return request;
    }

    /** Returns authored/template state for the editor without an exact byte owner. */
    public ApiRequest toWorkbenchApiRequest() {
        if (authoredRequest != null) {
            return copyRequestWithoutExactTransport(authoredRequest);
        }
        ApiRequest request = toAuthoredApiRequest();
        if (request.exactHttpRequest != null) {
            request.exactHttpRequest =
                    burp.models.ExactHttpRequestSnapshot.copyMetadataOnly(request.exactHttpRequest);
        }
        return request;
    }

    /**
     * Compatibility alias for persisted callers. History actions should use
     * {@link #toAuthoredApiRequest()} to make replay ownership explicit.
     */
    public ApiRequest toApiRequest() {
        return toAuthoredApiRequest();
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
        ApiRequest request = toAuthoredApiRequest();
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
        if (rawRequestSent != null && rawRequestSent.length > 0) {
            size += rawRequestSent.length;
        } else if (rawRequestSentText != null) {
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
        if (authoredRequest != null && authoredRequest.exactHttpRequest != null) {
            size += utf8Length(authoredRequest.exactHttpRequest.serviceHost);
            size += utf8Length(authoredRequest.exactHttpRequest.httpVersion);
            size += utf8Length(authoredRequest.exactHttpRequest.sourceContext);
            size += utf8Length(authoredRequest.exactHttpRequest.invalidationReason);
            size += utf8Length(authoredRequest.exactHttpRequest.semanticFingerprint);
        }
        if (authoredExactRequestBytes != null && authoredExactRequestBytes != rawRequestSent) {
            size += authoredExactRequestBytes.length;
        } else if (authoredExactRequestBytes == null
                && authoredRequest != null
                && authoredRequest.exactHttpRequest != null
                && authoredRequest.exactHttpRequest.rawRequestBytes != null
                && authoredRequest.exactHttpRequest.rawRequestBytes != rawRequestSent) {
                size += authoredRequest.exactHttpRequest.rawRequestBytes.length;
        }
        if (authoredExactPayloadRef != null) {
            size += 160L;
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
            if (request.body.raw != null) {
                return request.body.raw;
            }
            if (request.hasDerivedExactTextBody()) {
                return burp.models.ExactHttpRequestSnapshot.textBody(
                        request.exactHttpRequest.rawRequestBytes);
            }
            return "";
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

    private static ApiRequest copyRequestWithoutExactTransport(ApiRequest request) {
        if (request == null) {
            return null;
        }
        return request.applyToWithExactTransportMetadata(new ApiRequest());
    }

    private void restoreAuthoredExactTransport(ApiRequest request) {
        if (request == null || (authoredExactPayloadRef == null
                && (authoredExactRequestBytes == null || authoredExactRequestBytes.length == 0))) {
            return;
        }
        if (request.exactHttpRequest == null) {
            request.exactHttpRequest = new burp.models.ExactHttpRequestSnapshot();
        }
        request.exactHttpRequest.payloadRef = authoredExactPayloadRef != null
                ? authoredExactPayloadRef.copy() : null;
        request.exactHttpRequest.payloadUnavailable = authoredExactPayloadUnavailable;
        request.exactHttpRequest.rawRequestBytes = authoredExactRequestBytes != null
                ? authoredExactRequestBytes.clone() : null;
    }

    private static int utf8Length(String value) {
        return value != null ? value.getBytes(StandardCharsets.UTF_8).length : 0;
    }

}
