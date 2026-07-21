package burp.utils;

import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.parser.HistoryRawHttpMessageParser;

public final class ExactHttpRequestSnapshotMigrationSupport {
    private ExactHttpRequestSnapshotMigrationSupport() {
    }

    public static boolean migrateLegacySemanticFingerprint(ApiRequest request) {
        if (request == null || request.exactHttpRequest == null) {
            return false;
        }
        ExactHttpRequestSnapshot snapshot = request.exactHttpRequest;
        String stored = snapshot.semanticFingerprint;
        if (stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.equals(request.computeSemanticFingerprint())) {
            return false;
        }
        if (!stored.equals(request.computeLegacySemanticFingerprintV1())) {
            return false;
        }
        if (snapshot.httpVersion == null || snapshot.httpVersion.isBlank()) {
            String inferred = inferHttpVersion(snapshot.rawRequestBytes);
            if (inferred != null) {
                snapshot.httpVersion = inferred;
            }
        }
        snapshot.semanticFingerprint = request.computeSemanticFingerprint();
        return true;
    }

    private static String inferHttpVersion(byte[] rawRequestBytes) {
        if (rawRequestBytes == null || rawRequestBytes.length == 0) {
            return null;
        }
        HistoryRawHttpMessageParser.ParsedRawHttpMessage parsed =
                HistoryRawHttpMessageParser.parseRequest(rawRequestBytes, null);
        if (!parsed.isTrustedRequest()) {
            return null;
        }
        if ("HTTP/1.0".equalsIgnoreCase(parsed.httpVersion())) {
            return "HTTP/1.0";
        }
        if ("HTTP/1.1".equalsIgnoreCase(parsed.httpVersion())) {
            return "HTTP/1.1";
        }
        return null;
    }
}
