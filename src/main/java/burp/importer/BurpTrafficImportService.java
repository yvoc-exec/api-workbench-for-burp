package burp.importer;

import burp.history.HistoryBodyTruncator;
import burp.history.HistoryEntry;
import burp.history.HistoryHeader;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryResponseSnapshot;
import burp.history.HistorySource;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.HistoryRawHttpMessageParser;
import burp.parser.HistoryRawHttpMessageParser.ParsedRawHttpMessage;
import burp.ui.tree.RequestTreeNamingPolicy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BurpTrafficImportService {
    private final Clock clock;

    public BurpTrafficImportService() {
        this(Clock.systemUTC());
    }

    public BurpTrafficImportService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public BurpTrafficConversionResult convert(List<BurpTrafficSelection> selections) {
        return convert(selections, HistoryRetentionPolicy.defaultPolicy());
    }

    public BurpTrafficConversionResult convert(List<BurpTrafficSelection> selections,
                                               HistoryRetentionPolicy retentionPolicy) {
        BurpTrafficConversionResult result = new BurpTrafficConversionResult();
        if (selections == null) {
            return result;
        }
        List<ApiRequest> convertedRequests = new ArrayList<>();
        List<HistoryEntry> convertedHistoryByRequest = new ArrayList<>();
        for (BurpTrafficSelection selection : selections) {
            try {
                ApiRequest request = convertRequest(selection);
                convertedRequests.add(request);
                convertedHistoryByRequest.add(convertHistory(selection, request, retentionPolicy));
            } catch (ConversionException e) {
                result.failures.add(new BurpTrafficConversionResult.Failure(
                        selection != null ? selection.encounterIndex : -1,
                        e.reasonCode,
                        e.getMessage()));
            }
        }
        if (!result.failures.isEmpty()) {
            return result;
        }
        result.requests.addAll(convertedRequests);
        result.historyEntries.addAll(convertedHistoryByRequest);
        return result;
    }

    public ApiRequest convertRequest(BurpTrafficSelection selection) {
        if (selection == null || selection.rawRequestBytes == null || selection.rawRequestBytes.length == 0) {
            throw new ConversionException("MISSING_RAW_REQUEST", "Traffic import requires a raw HTTP request.");
        }
        ParsedRawHttpMessage parsed = HistoryRawHttpMessageParser.parseRequest(selection.rawRequestBytes, null);
        if (!parsed.isTrustedRequest()) {
            throw new ConversionException("MALFORMED_HTTP_REQUEST", parsed.parseWarning().isBlank()
                    ? "Traffic import request is malformed."
                    : parsed.parseWarning());
        }

        ApiRequest request = new ApiRequest();
        request.id = UUID.randomUUID().toString();
        request.method = !parsed.method().isBlank() ? parsed.method() : fallbackMethod(selection);
        request.url = buildAbsoluteUrl(selection, parsed.target(), request.method);
        request.name = suggestedName(selection, request.method, parsed.target());
        request.headers = new ArrayList<>();
        for (HistoryHeader header : parsed.headers()) {
            if (header != null) {
                request.headers.add(new ApiRequest.Header(header.name, header.value, false));
            }
        }
        int bodyOffset = bodyOffset(selection.rawRequestBytes);
        request.body = buildBody(bodyOffset >= 0
                ? selection.rawRequestBytes.length - bodyOffset
                : 0);
        request.editorMaterialized = true;
        request.buildMode = ApiRequest.BuildMode.EXACT_HTTP;
        request.disabled = false;
        request.preRequestScripts = new ArrayList<>();
        request.postResponseScripts = new ArrayList<>();
        request.scriptBlocks = new ArrayList<>();
        request.variables = new ArrayList<>();
        request.suppressedAutoHeaders = new java.util.LinkedHashSet<>();
        request.exactHttpRequest = exactSnapshot(selection, request, bodyOffset);
        return request;
    }

    public HistoryEntry convertHistory(BurpTrafficSelection selection, ApiRequest request) {
        return convertHistory(selection, request, HistoryRetentionPolicy.defaultPolicy());
    }

    public HistoryEntry convertHistory(BurpTrafficSelection selection,
                                       ApiRequest request,
                                       HistoryRetentionPolicy retentionPolicy) {
        if (selection == null || selection.rawResponseBytes == null || selection.rawResponseBytes.length == 0 || request == null) {
            return null;
        }
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        policy.normalize();
        HistoryEntry entry = new HistoryEntry();
        entry.id = UUID.randomUUID().toString();
        entry.timestamp = clock.instant();
        entry.source = HistorySource.BURP_TRAFFIC;
        entry.collectionId = "";
        entry.collectionName = request.sourceCollection;
        entry.requestId = request.id;
        entry.requestName = request.name;
        entry.requestSnapshot = boundedRequestSnapshot(request, selection.rawRequestBytes, policy);
        entry.requestSizeBytes = selection.rawRequestBytes.length;
        entry.requestSent = false;
        entry.preflightStatus = "RECORDED_ONLY";
        entry.metadataSummaryText = "Source context: " + safeContext(selection.sourceContext)
                + "\nRequest representation: EXACT_RAW"
                + "\nResponse representation: STORED_RAW_COMPONENTS";

        HistoryResponseSnapshot snapshot = parseResponse(selection.rawResponseBytes, policy);
        entry.responseSnapshot = snapshot;
        entry.responseSizeBytes = selection.rawResponseBytes.length;
        entry.statusCode = snapshot.statusCode;
        entry.ensureDefaults();
        return entry;
    }

    private HistoryRequestSnapshot boundedRequestSnapshot(ApiRequest request,
                                                          byte[] rawRequestBytes,
                                                          HistoryRetentionPolicy policy) {
        HistoryRequestSnapshot snapshot = HistoryRequestSnapshot.fromWithoutExactTransport(request);
        int bodyOffset = bodyOffset(rawRequestBytes);
        int originalBodyLength = bodyOffset >= 0 ? rawRequestBytes.length - bodyOffset : 0;
        int storedBodyLength = (int) Math.min(originalBodyLength, policy.maxRequestBodyBytesPerEntry);
        int storedLength = bodyOffset >= 0
                ? bodyOffset + storedBodyLength
                : rawRequestBytes.length;
        snapshot.rawRequestSent = Arrays.copyOf(rawRequestBytes, storedLength);
        snapshot.rawRequestSentText = null;
        snapshot.originalRawBodyLength = originalBodyLength;
        snapshot.storedRawBodyLength = storedBodyLength;
        snapshot.fullRawBodySha256 = originalBodyLength > 0
                ? HistoryBodyTruncator.sha256Hex(rawRequestBytes, bodyOffset, originalBodyLength)
                : "";
        snapshot.rawBodyTruncated = storedBodyLength < originalBodyLength;
        snapshot.rawTruncationReason = snapshot.rawBodyTruncated
                ? HistoryBodyTruncator.RAW_REQUEST_BODY_LIMIT_REASON
                : "";
        if (originalBodyLength > policy.maxRequestBodyBytesPerEntry) {
            snapshot.discardAuthoredExactTransport("HISTORY_RETENTION_LIMIT");
        }
        snapshot.canonicalizeExactTransportOwnership();
        return snapshot;
    }

    private HistoryResponseSnapshot parseResponse(byte[] rawResponseBytes,
                                                  HistoryRetentionPolicy policy) {
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        int boundary = indexOf(rawResponseBytes, new byte[]{'\r', '\n', '\r', '\n'});
        int separatorLength = 4;
        if (boundary < 0) {
            boundary = indexOf(rawResponseBytes, new byte[]{'\n', '\n'});
            separatorLength = 2;
        }
        int headerLength = boundary >= 0 ? boundary : rawResponseBytes.length;
        String headerText = new String(rawResponseBytes, 0, headerLength, StandardCharsets.ISO_8859_1);
        List<String> headerLines = splitLines(headerText);
        if (!headerLines.isEmpty()) {
            String[] parts = headerLines.get(0).trim().split("\\s+", 3);
            if (parts.length >= 2) {
                try {
                    snapshot.statusCode = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    snapshot.statusCode = -1;
                }
                if (parts.length >= 3) {
                    snapshot.reasonPhrase = parts[2];
                }
            }
        }
        for (int i = 1; i < headerLines.size(); i++) {
            String line = headerLines.get(i);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon);
            String value = trimOptionalWhitespace(line.substring(colon + 1));
            snapshot.headers.add(new HistoryHeader(name, value, false));
            if ("content-type".equalsIgnoreCase(name)) {
                snapshot.mimeType = value;
            }
        }
        if (boundary >= 0) {
            int bodyOffset = boundary + separatorLength;
            if (bodyOffset < rawResponseBytes.length) {
                int originalBodyLength = rawResponseBytes.length - bodyOffset;
                int storedBodyLength = (int) Math.min(
                        originalBodyLength, policy.maxResponseBodyBytesPerEntry);
                snapshot.body = Arrays.copyOfRange(
                        rawResponseBytes, bodyOffset, bodyOffset + storedBodyLength);
                snapshot.originalBodyLength = originalBodyLength;
                snapshot.storedBodyLength = storedBodyLength;
                snapshot.fullBodySha256 = HistoryBodyTruncator.sha256Hex(
                        rawResponseBytes, bodyOffset, originalBodyLength);
                snapshot.bodyTruncated = storedBodyLength < originalBodyLength;
                snapshot.truncationReason = snapshot.bodyTruncated
                        ? HistoryBodyTruncator.RESPONSE_BODY_LIMIT_REASON
                        : "";
            }
        }
        if (snapshot.body == null) {
            snapshot.body = new byte[0];
            snapshot.originalBodyLength = 0L;
            snapshot.storedBodyLength = 0L;
            snapshot.fullBodySha256 = "";
            snapshot.bodyTruncated = false;
            snapshot.truncationReason = "";
        }
        return snapshot;
    }

    private ExactHttpRequestSnapshot exactSnapshot(BurpTrafficSelection selection,
                                                   ApiRequest request,
                                                   int bodyOffset) {
        ExactHttpRequestSnapshot snapshot = new ExactHttpRequestSnapshot();
        snapshot.rawRequestBytes = selection.rawRequestBytes;
        snapshot.serviceHost = selection.serviceHost;
        snapshot.servicePort = normalizedPort(selection);
        snapshot.secure = selection.secure;
        snapshot.pristine = true;
        snapshot.binaryBody = bodyOffset >= 0
                && bodyOffset < selection.rawRequestBytes.length
                && !isUtf8Text(selection.rawRequestBytes, bodyOffset);
        snapshot.sourceContext = safeContext(selection.sourceContext);
        snapshot.invalidationReason = "";
        snapshot.semanticFingerprint = request.computeSemanticFingerprint();
        return snapshot;
    }

    private ApiRequest.Body buildBody(int bodyLength) {
        if (bodyLength <= 0) {
            return null;
        }
        ApiRequest.Body body = new ApiRequest.Body();
        body.mode = "raw";
        body.raw = null;
        return body;
    }

    private boolean isUtf8Text(byte[] rawRequestBytes, int bodyOffset) {
        String text = new String(rawRequestBytes, bodyOffset,
                rawRequestBytes.length - bodyOffset, StandardCharsets.UTF_8);
        byte[] roundTrip = text.getBytes(StandardCharsets.UTF_8);
        int bodyLength = rawRequestBytes.length - bodyOffset;
        if (roundTrip.length != bodyLength) {
            return false;
        }
        for (int i = 0; i < bodyLength; i++) {
            if (roundTrip[i] != rawRequestBytes[bodyOffset + i]) {
                return false;
            }
        }
        return true;
    }

    private String fallbackMethod(BurpTrafficSelection selection) {
        if (selection != null && selection.fallbackMethod != null && !selection.fallbackMethod.isBlank()) {
            return selection.fallbackMethod.trim().toUpperCase(Locale.ROOT);
        }
        return "GET";
    }

    private String suggestedName(BurpTrafficSelection selection, String method, String target) {
        String provided = selection != null ? selection.suggestedDisplayName : null;
        if (provided != null && !provided.isBlank()) {
            String normalized = RequestTreeNamingPolicy.normalizeTreeLabel(provided.replace('\\', ' ').replace('/', ' '));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        String safeTarget = sanitizeTargetForName(target);
        String base = (method != null ? method : "GET") + " " + safeTarget;
        String normalized = RequestTreeNamingPolicy.normalizeTreeLabel(base);
        return normalized.isBlank() ? "Imported Request" : normalized;
    }

    private String sanitizeTargetForName(String target) {
        if (target == null || target.isBlank()) {
            return "/";
        }
        String value = target;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = URI.create(value);
                value = uri.getRawPath();
                if (value == null || value.isBlank()) {
                    value = "/";
                }
            } catch (Exception ignored) {
                value = "/";
            }
        } else if (value.contains("?")) {
            value = value.substring(0, value.indexOf('?'));
        }
        return value.isBlank() ? "/" : value;
    }

    private String buildAbsoluteUrl(BurpTrafficSelection selection, String target, String method) {
        if (selection == null || selection.serviceHost == null || selection.serviceHost.isBlank()) {
            throw new ConversionException("MISSING_SERVICE", "Traffic import requires a host.");
        }
        String scheme = selection.secure ? "https" : "http";
        String host = selection.serviceHost.contains(":") && !selection.serviceHost.startsWith("[")
                ? "[" + selection.serviceHost + "]"
                : selection.serviceHost;
        int port = normalizedPort(selection);
        boolean defaultPort = (selection.secure && port == 443) || (!selection.secure && port == 80);
        String authority = scheme + "://" + host + (defaultPort ? "" : ":" + port);
        if (target == null || target.isBlank()) {
            target = "/";
        }
        if ("*".equals(target)) {
            return authority + "/*";
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                URI uri = URI.create(target);
                String path = uri.getRawPath();
                if (path == null || path.isBlank()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                    path += "?" + uri.getRawQuery();
                }
                return authority + path;
            } catch (Exception e) {
                throw new ConversionException("INVALID_REQUEST_TARGET", "Traffic import request target is invalid.");
            }
        }
        if (target.startsWith("/")) {
            return authority + target;
        }
        if ("CONNECT".equalsIgnoreCase(method)) {
            return authority + "/";
        }
        return authority + "/" + target;
    }

    private int normalizedPort(BurpTrafficSelection selection) {
        return selection.servicePort > 0 ? selection.servicePort : (selection.secure ? 443 : 80);
    }

    private int bodyOffset(byte[] messageBytes) {
        int crlfBoundary = indexOf(messageBytes, new byte[]{'\r', '\n', '\r', '\n'});
        int lfBoundary = crlfBoundary >= 0 ? -1 : indexOf(messageBytes, new byte[]{'\n', '\n'});
        int boundary = crlfBoundary >= 0 ? crlfBoundary : lfBoundary;
        int separatorLength = crlfBoundary >= 0 ? 4 : (lfBoundary >= 0 ? 2 : 0);
        if (boundary < 0 || boundary + separatorLength >= messageBytes.length) {
            return -1;
        }
        return boundary + separatorLength;
    }

    private static String safeContext(String sourceContext) {
        if (sourceContext == null || sourceContext.isBlank()) {
            return "Burp";
        }
        return sourceContext.replaceAll("[^A-Za-z0-9 _.-]", "_");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || haystack.length == 0 || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] split = text.replace("\r", "").split("\n", -1);
        java.util.Collections.addAll(lines, split);
        return lines;
    }

    private static String trimOptionalWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static final class ConversionException extends RuntimeException {
        private final String reasonCode;

        private ConversionException(String reasonCode, String safeMessage) {
            super(safeMessage != null ? safeMessage : "Traffic import failed.");
            this.reasonCode = reasonCode != null ? reasonCode : "";
        }
    }
}
