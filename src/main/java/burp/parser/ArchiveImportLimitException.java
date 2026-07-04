package burp.parser;

import java.io.IOException;
import java.util.Locale;

public class ArchiveImportLimitException extends IOException {
    public enum Reason {
        ENTRY_COUNT,
        TOTAL_UNCOMPRESSED_BYTES,
        ENTRY_UNCOMPRESSED_BYTES,
        PATH_DEPTH,
        COMPRESSION_RATIO,
        UNSAFE_PATH,
        DUPLICATE_OUTPUT_PATH
    }

    private final Reason reason;
    private final String entryName;
    private final double observedValue;
    private final double configuredLimit;

    public ArchiveImportLimitException(Reason reason,
                                       String entryName,
                                       double observedValue,
                                       double configuredLimit) {
        super(buildMessage(reason, entryName, configuredLimit));
        this.reason = reason;
        this.entryName = entryName;
        this.observedValue = observedValue;
        this.configuredLimit = configuredLimit;
    }

    public Reason getReason() {
        return reason;
    }

    public String getEntryName() {
        return entryName;
    }

    public double getObservedValue() {
        return observedValue;
    }

    public double getConfiguredLimit() {
        return configuredLimit;
    }

    private static String buildMessage(Reason reason, String entryName, double configuredLimit) {
        String suffix;
        if (reason == null) {
            return "Archive import rejected.";
        }
        switch (reason) {
            case ENTRY_COUNT:
                suffix = "entry count exceeds " + formatNumber(configuredLimit) + ".";
                break;
            case TOTAL_UNCOMPRESSED_BYTES:
                suffix = "total uncompressed bytes exceed " + formatNumber(configuredLimit) + ".";
                break;
            case ENTRY_UNCOMPRESSED_BYTES:
                suffix = "entry exceeds " + formatNumber(configuredLimit) + " uncompressed bytes.";
                break;
            case PATH_DEPTH:
                suffix = "path depth exceeds " + formatNumber(configuredLimit) + ".";
                break;
            case COMPRESSION_RATIO:
                suffix = "compression ratio exceeds " + formatNumber(configuredLimit) + ".";
                break;
            case UNSAFE_PATH:
                suffix = "unsafe path" + safePathSuffix(entryName) + ".";
                break;
            case DUPLICATE_OUTPUT_PATH:
                suffix = "duplicate output path" + safePathSuffix(entryName) + ".";
                break;
            default:
                suffix = "archive import rejected.";
                break;
        }
        return "Archive import rejected: " + suffix;
    }

    private static String safePathSuffix(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return "";
        }
        return " '" + entryName.replace("'", "") + "'";
    }

    private static String formatNumber(double value) {
        if (Double.isFinite(value) && Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
