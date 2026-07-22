package burp.history;

public record HistoryAdmissionResult(
        boolean accepted,
        String storedEntryId,
        HistoryAdmissionRejectionReason rejectionReason,
        int entriesAccepted,
        int entriesEvicted,
        long bytesBefore,
        long bytesAfter,
        long configuredByteLimit,
        int configuredEntryLimit
) {
    public HistoryAdmissionResult {
        storedEntryId = storedEntryId != null && !storedEntryId.isBlank() ? storedEntryId : null;
        entriesAccepted = Math.max(0, entriesAccepted);
        entriesEvicted = Math.max(0, entriesEvicted);
        bytesBefore = Math.max(0L, bytesBefore);
        bytesAfter = Math.max(0L, bytesAfter);
        configuredByteLimit = Math.max(0L, configuredByteLimit);
        configuredEntryLimit = Math.max(0, configuredEntryLimit);
        if (accepted) {
            rejectionReason = null;
        } else if (rejectionReason == null) {
            rejectionReason = HistoryAdmissionRejectionReason.POLICY_REJECTED;
        }
    }

    public static HistoryAdmissionResult success(String storedEntryId,
                                                  int entriesAccepted,
                                                  int entriesEvicted,
                                                  long bytesBefore,
                                                  long bytesAfter,
                                                  HistoryRetentionPolicy policy) {
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(policy);
        return new HistoryAdmissionResult(
                true,
                storedEntryId,
                null,
                entriesAccepted,
                entriesEvicted,
                bytesBefore,
                bytesAfter,
                safePolicy.maxTotalStoredBytes,
                safePolicy.maxEntries);
    }

    public static HistoryAdmissionResult rejection(HistoryAdmissionRejectionReason reason,
                                                    String existingEntryId,
                                                    long retainedBytes,
                                                    HistoryRetentionPolicy policy) {
        HistoryRetentionPolicy safePolicy = HistoryRetentionPolicy.copyOf(policy);
        return new HistoryAdmissionResult(
                false,
                existingEntryId,
                reason,
                0,
                0,
                retainedBytes,
                retainedBytes,
                safePolicy.maxTotalStoredBytes,
                safePolicy.maxEntries);
    }
}
